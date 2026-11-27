import { useCallback, useEffect, useRef, useState } from 'react';
import type { ApiRequest } from '../api/boardApi';
import { fetchTaskGitSummaries } from '../api/gitciApi';
import { ApiError } from '../types/board';
import type { TaskGitSummary } from '../types/gitci';

function formatGitSummaryError(reason: unknown): string {
  if (reason instanceof ApiError) {
    if (reason.status === 429) {
      return `Tải thông tin Git/CI bị giới hạn tốc độ. Thử lại sau ${reason.problem?.retryAfterSeconds ?? 5} giây.`;
    }
    if (reason.status === 401) return 'Phiên đăng nhập hết hạn; chưa tải được thông tin Git/CI.';
    if (reason.status === 403 || reason.status === 404) return 'Không còn quyền xem dữ liệu Git/CI của workspace này.';
    if (reason.status >= 500 || reason.status === 0) return 'Không tải được trạng thái Git/CI lúc này. Bảng Kanban vẫn hoạt động bình thường.';
    return reason.message;
  }
  return 'Không tải được trạng thái Git/CI lúc này. Bảng Kanban vẫn hoạt động bình thường.';
}

function isAbortError(reason: unknown): boolean {
  return (
    (reason instanceof DOMException && reason.name === 'AbortError') ||
    (reason instanceof Error && reason.name === 'AbortError') ||
    (reason instanceof ApiError && reason.message === 'Yêu cầu đã bị hủy.')
  );
}

export interface UseBoardGitSummariesResult {
  summariesByTaskId: ReadonlyMap<string, TaskGitSummary>;
  loading: boolean;
  error: string | null;
  stale: boolean;
  refreshAll(taskIds: readonly string[]): void;
  refreshSubset(taskIds: readonly string[], options?: { debounced?: boolean }): void;
}

/**
 * Manages board-scoped Git & CI badge summaries with strict context-generation guards,
 * AbortController cancellation, debounced subset refreshes for realtime STOMP signals,
 * and degraded-mode isolation so GitCI read failures never block Kanban interactions.
 */
export function useBoardGitSummaries({
  request,
  workspaceId,
  boardId,
  sessionGeneration,
  enabled,
}: {
  request: ApiRequest;
  workspaceId: string;
  boardId?: string;
  sessionGeneration: number;
  enabled: boolean;
}): UseBoardGitSummariesResult {
  const [summariesByTaskId, setSummariesByTaskId] = useState<ReadonlyMap<string, TaskGitSummary>>(() => new Map());
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [stale, setStale] = useState(false);

  const contextGenerationRef = useRef(0);
  const fullControllerRef = useRef<AbortController | null>(null);
  const subsetControllerRef = useRef<AbortController | null>(null);
  const subsetTimerRef = useRef<number | undefined>(undefined);
  const pendingSubsetRef = useRef(new Set<string>());
  const hasLoadedOnceRef = useRef(false);

  useEffect(() => {
    contextGenerationRef.current += 1;
    fullControllerRef.current?.abort();
    subsetControllerRef.current?.abort();
    fullControllerRef.current = null;
    subsetControllerRef.current = null;
    if (subsetTimerRef.current !== undefined) {
      window.clearTimeout(subsetTimerRef.current);
      subsetTimerRef.current = undefined;
    }
    pendingSubsetRef.current.clear();
    hasLoadedOnceRef.current = false;
    setSummariesByTaskId(new Map());
    setLoading(false);
    setError(null);
    setStale(false);
  }, [boardId, enabled, sessionGeneration, workspaceId]);

  const executeFetch = useCallback(async (taskIds: readonly string[], mode: 'replace' | 'merge') => {
    if (!enabled || !workspaceId || !boardId) return;
    const uniqueIds = [...new Set(taskIds.filter((id) => id.trim().length > 0))];
    const generation = contextGenerationRef.current;

    if (uniqueIds.length === 0) {
      if (mode === 'replace') {
        setSummariesByTaskId(new Map());
        setError(null);
        setStale(false);
      }
      return;
    }

    const controllerRef = mode === 'replace' ? fullControllerRef : subsetControllerRef;
    controllerRef.current?.abort();
    const controller = new AbortController();
    controllerRef.current = controller;

    if (mode === 'replace') setLoading(true);
    try {
      const fetched = await fetchTaskGitSummaries(request, workspaceId, uniqueIds, controller.signal);
      if (controller.signal.aborted || generation !== contextGenerationRef.current) return;
      hasLoadedOnceRef.current = true;
      setSummariesByTaskId((current) => {
        if (mode === 'replace') return fetched;
        const next = new Map(current);
        for (const id of uniqueIds) {
          const item = fetched.get(id);
          if (item) next.set(id, item);
          else next.delete(id);
        }
        return next;
      });
      setError(null);
      setStale(false);
    } catch (reason) {
      if (controller.signal.aborted || generation !== contextGenerationRef.current || isAbortError(reason)) return;
      if (reason instanceof ApiError && (reason.status === 401 || reason.status === 403 || reason.status === 404)) {
        setSummariesByTaskId(new Map());
        setStale(false);
        setError(formatGitSummaryError(reason));
        return;
      }
      setError(formatGitSummaryError(reason));
      if (hasLoadedOnceRef.current) setStale(true);
    } finally {
      if (controllerRef.current === controller) controllerRef.current = null;
      if (mode === 'replace' && generation === contextGenerationRef.current) setLoading(false);
    }
  }, [boardId, enabled, request, workspaceId]);

  const refreshAll = useCallback((taskIds: readonly string[]) => {
    void executeFetch(taskIds, 'replace');
  }, [executeFetch]);

  const refreshSubset = useCallback((taskIds: readonly string[], options?: { debounced?: boolean }) => {
    if (!enabled || !workspaceId || !boardId) return;
    for (const id of taskIds) {
      if (id.trim().length > 0) pendingSubsetRef.current.add(id);
    }
    if (pendingSubsetRef.current.size === 0) return;

    const flush = () => {
      subsetTimerRef.current = undefined;
      const batch = [...pendingSubsetRef.current];
      pendingSubsetRef.current.clear();
      if (batch.length > 0) void executeFetch(batch, 'merge');
    };

    if (options?.debounced === false) {
      if (subsetTimerRef.current !== undefined) {
        window.clearTimeout(subsetTimerRef.current);
        subsetTimerRef.current = undefined;
      }
      flush();
      return;
    }

    if (subsetTimerRef.current !== undefined) window.clearTimeout(subsetTimerRef.current);
    subsetTimerRef.current = window.setTimeout(flush, 150);
  }, [boardId, enabled, executeFetch, workspaceId]);

  useEffect(() => () => {
    fullControllerRef.current?.abort();
    subsetControllerRef.current?.abort();
    if (subsetTimerRef.current !== undefined) window.clearTimeout(subsetTimerRef.current);
  }, []);

  return { summariesByTaskId, loading, error, stale, refreshAll, refreshSubset };
}
