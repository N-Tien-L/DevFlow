import { lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { DragDropProvider } from '@dnd-kit/react';
import type { DragEndEvent, DragOverEvent, DragStartEvent } from '@dnd-kit/react';
import { isSortable } from '@dnd-kit/react/sortable';
import clsx from 'clsx';
import { applyColumnOrder, cloneColumns, locateTask, moveTaskBefore, reorderColumnById } from '../board/mutations';
import { getBoard, listBoards, listWorkspaceMembers, listWorkspaces, moveTask, reorderColumn } from '../api/boardApi';
import { useAuth } from '../auth/AuthProvider';
import { useBoardSync, type BoardConnectionStatus } from '../hooks/useBoardSync';
import { useNotificationSync } from '../hooks/useNotificationSync';
import { BoardColumnView } from '../components/board/BoardColumnView';
import { BoardTopBar } from '../components/layout/BoardTopBar';
import {
  AlertCircleIcon,
  AlertTriangleIcon,
  ArchiveIcon,
  FilterIcon,
  InfoIcon,
  KanbanIcon,
  LayoutGridIcon,
  PlusIcon,
  RefreshIcon,
  SearchIcon,
  UserIcon,
  XIcon,
} from '../components/common/Icons';
import {
  Badge,
  EmptyStatePanel,
  LoadingSpinner,
  PRIORITY_LABEL,
  RoleBadge,
} from '../components/common/UiPrimitives';
import { emptyBoardSyncScopes, fetchBoardSnapshot, fetchEveryTaskPage, hasBoardSyncWork, mapWithConcurrency, MAX_PAGES_PER_COLUMN, mergeBoardSyncScopes, type BoardSyncScopes } from '../board/boardSyncCoordinator';
import type { BoardMutationMessage } from '../realtime/boardMutation';
const TaskDetailModal = lazy(() => import('../components/board/TaskDetailModal').then((module) => ({ default: module.TaskDetailModal })));
import { ApiError, type BoardColumn, type BoardPageResponse, type BoardResponse, type BoardState, type BoardSummary, type ColumnResponse, type TaskDetail, type TaskPriority, type WorkspaceMember, type WorkspaceSummary } from '../types/board';

const PRIORITIES: TaskPriority[] = ['LOW', 'MEDIUM', 'HIGH', 'URGENT'];

interface DragData extends Record<string, unknown> {
  kind: 'task' | 'column';
  taskId?: string;
  columnId: string;
}

function toBoardState(board: BoardResponse): BoardState {
  const columns: BoardColumn[] = [...board.columns]
    .sort((left, right) => left.position - right.position)
    .map((column) => ({ ...column, tasks: [], totalElements: 0, totalPages: 1, loadedPages: 0, complete: false, loading: true, error: null }));
  return { ...board, columns };
}

function errorMessage(reason: unknown) {
  if (reason instanceof ApiError) {
    const code = reason.problem?.code;
    if (code === 'BOARD_ARCHIVED') return 'Board đã lưu trữ nên không thể sắp xếp.';
    if (code === 'INVALID_POSITION') return 'Vị trí thay đổi không còn hợp lệ. Dữ liệu đã được tải lại.';
    if (code === 'TASK_LIMIT_REACHED') return 'Cột đã đạt giới hạn số công việc.';
    if (code === 'BOT_CHALLENGE_REQUIRED' || code === 'BOT_CHALLENGE_REJECTED') return 'Không xác minh được thao tác. Vui lòng thử lại.';
    if (code === 'BOT_PROTECTION_UNAVAILABLE') return 'Dịch vụ bảo vệ đang tạm thời không khả dụng.';
    if (code === 'RATE_LIMIT_EXCEEDED') return `Bạn thao tác quá nhanh. Thử lại sau ${reason.problem?.retryAfterSeconds ?? 5} giây.`;
    if (reason.status === 401) return 'Phiên đăng nhập hết hạn. Hãy đăng nhập lại rồi tải lại board.';
    if (reason.status === 403) return 'Bạn không có quyền thực hiện thay đổi này.';
    if (reason.status === 404) return 'Board hoặc công việc không còn tồn tại.';
    return reason.message;
  }
  return 'Đã có lỗi xảy ra. Vui lòng thử lại.';
}

export default function BoardPage() {
  const { boardId } = useParams();
  const navigate = useNavigate();
  // Chosen on /workspaces; ignored unless it is one of the caller's workspaces.
  const requestedWorkspaceId = useSearchParams()[0].get('workspace');
  const { user, ready: authReady, request, signOut, getValidAccessToken, sessionGeneration } = useAuth();
  useNotificationSync();

  const [workspaces, setWorkspaces] = useState<WorkspaceSummary[]>([]);
  const [loadingWorkspaces, setLoadingWorkspaces] = useState(true);
  const [workspaceId, setWorkspaceId] = useState('');
  const [workspaceMembers, setWorkspaceMembers] = useState<WorkspaceMember[]>([]);
  const [boards, setBoards] = useState<BoardSummary[]>([]);
  const [board, setBoard] = useState<BoardState | null>(null);
  const boardRef = useRef<BoardState | null>(null);
  const [previewColumns, setPreviewColumns] = useState<BoardColumn[] | null>(null);
  const previewRef = useRef<BoardColumn[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [filterText, setFilterText] = useState('');
  const [assigneeFilter, setAssigneeFilter] = useState('');
  const [priorityFilter, setPriorityFilter] = useState('');
  const [mobileFiltersOpen, setMobileFiltersOpen] = useState(false);
  const [pending, setPending] = useState(false);
  const [syncBlocked, setSyncBlocked] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [activeDragName, setActiveDragName] = useState('');
  const [loadingBoards, setLoadingBoards] = useState(false);
  const [selectedTaskId, setSelectedTaskId] = useState<string | null>(null);
  const [connectionStatus, setConnectionStatus] = useState<BoardConnectionStatus>('idle');
  const [taskDetailRefreshVersion, setTaskDetailRefreshVersion] = useState(0);
  const [commentsRefreshVersion, setCommentsRefreshVersion] = useState(0);
  const [selectedTaskDeleted, setSelectedTaskDeleted] = useState(false);

  const modalReturnFocusRef = useRef<HTMLElement | null>(null);
  const generationRef = useRef(0);
  const connectionStatusRef = useRef<BoardConnectionStatus>('idle');
  const syncBlockedRef = useRef(syncBlocked);
  const activeDragRef = useRef(false);
  const dragInvalidatedRef = useRef(false);
  const connectionResyncTimerRef = useRef<number | undefined>(undefined);
  const malformedResyncTimerRef = useRef<number | undefined>(undefined);
  const periodicSyncTimerRef = useRef<number | undefined>(undefined);
  const syncRetryTimerRef = useRef<number | undefined>(undefined);
  const syncDebounceTimerRef = useRef<number | undefined>(undefined);
  const retryAttemptRef = useRef(0);
  const lastSyncBatchStartedAtRef = useRef(0);
  const reconciliationRunningRef = useRef(false);
  const reconciliationWorkerTokenRef = useRef(0);
  const eventRevisionRef = useRef(0);
  const seenEventIdsRef = useRef(new Map<string, number>());
  const queuedSyncRef = useRef<BoardSyncScopes>(emptyBoardSyncScopes());
  const debounceStartedAtRef = useRef(0);
  const reconciliationControllerRef = useRef<AbortController | null>(null);
  const initialLoadControllerRef = useRef<AbortController | null>(null);
  const selectedTaskIdRef = useRef(selectedTaskId);
  const columnLoadSequenceRef = useRef(new Map<string, number>());
  const workspaceIdRef = useRef(workspaceId);
  const boardIdRef = useRef(boardId);

  const displayColumns = useMemo(() => previewColumns ?? board?.columns ?? [], [board?.columns, previewColumns]);
  const activeFilterCount = [filterText.trim(), assigneeFilter, priorityFilter].filter(Boolean).length;
  const filtersActive = activeFilterCount > 0;
  const allTasksComplete = Boolean(board?.columns.every((column) => column.complete));
  const activeWorkspace = useMemo(
    () => workspaces.find((ws) => ws.id === workspaceId) ?? null,
    [workspaceId, workspaces],
  );
  const membersById = useMemo(
    () => new Map(workspaceMembers.map((member) => [member.userId, member])),
    [workspaceMembers],
  );
  const allAssignees = useMemo(
    () => [...new Set((board?.columns ?? []).flatMap((column) => column.tasks.map((task) => task.assigneeId).filter((id): id is string => Boolean(id))))].sort(),
    [board],
  );
  boardRef.current = board;
  syncBlockedRef.current = syncBlocked;
  selectedTaskIdRef.current = selectedTaskId;
  workspaceIdRef.current = workspaceId;
  boardIdRef.current = boardId;

  const updateColumn = useCallback((columnId: string, update: (column: BoardColumn) => BoardColumn, generation = generationRef.current) => {
    if (generation !== generationRef.current) return;
    setBoard((current) => current ? { ...current, columns: current.columns.map((column) => column.id === columnId ? update(column) : column) } : current);
  }, []);

  const loadColumn = useCallback(async (columnId: string, generation: number, replace = true, signal?: AbortSignal): Promise<boolean> => {
    if (!replace && syncBlockedRef.current) return false;
    const sequence = (columnLoadSequenceRef.current.get(columnId) ?? 0) + 1;
    columnLoadSequenceRef.current.set(columnId, sequence);
    updateColumn(columnId, (column) => ({ ...column, loading: true, error: null }), generation);
    try {
      const pageStart = replace ? 0 : (boardRef.current?.columns.find((column) => column.id === columnId)?.loadedPages ?? 0);
      const result = await fetchEveryTaskPage(request, columnId, pageStart, signal);
      if (signal?.aborted || generation !== generationRef.current || columnLoadSequenceRef.current.get(columnId) !== sequence) return false;
      updateColumn(columnId, (column) => ({
        ...column,
        tasks: replace ? result.tasks : [...new Map([...column.tasks, ...result.tasks].map((task) => [task.id, task])).values()].sort((left, right) => left.position - right.position),
        totalElements: result.page.totalElements,
        totalPages: result.page.totalPages,
        loadedPages: result.nextPage,
        complete: result.complete,
        loading: false,
        error: null,
      }), generation);
      if (!result.complete) setNotice(`Lượt tải cột dừng ở giới hạn ${MAX_PAGES_PER_COLUMN} trang. Dùng nút tải tiếp để xem toàn bộ.`);
      else if (connectionStatusRef.current === 'connected' && boardRef.current?.columns.every((column) => column.id === columnId || column.complete)) setNotice(null);
      return result.complete;
    } catch (reason) {
      if (signal?.aborted || generation !== generationRef.current || columnLoadSequenceRef.current.get(columnId) !== sequence) return false;
      updateColumn(columnId, (column) => ({ ...column, loading: false, error: errorMessage(reason) }), generation);
      return false;
    }
  }, [request, updateColumn]);

  const loadBoard = useCallback(async (id: string) => {
    initialLoadControllerRef.current?.abort();
    reconciliationControllerRef.current?.abort();
    columnLoadSequenceRef.current.clear();
    const controller = new AbortController();
    initialLoadControllerRef.current = controller;
    const generation = ++generationRef.current;
    setLoading(true);
    setError(null);
    setNotice(null);
    // A mutation started on the previous board only clears pending for its own generation.
    setPending(false);
    setSyncBlocked(true);
    syncBlockedRef.current = true;
    setBoard(null);
    try {
      const response = await getBoard(request, id, controller.signal);
      if (controller.signal.aborted || generation !== generationRef.current) return;
      const initial = toBoardState(response);
      setBoard(initial);
      setWorkspaceId(response.workspaceId);
      const columnIds = initial.columns.map((column) => column.id);
      await mapWithConcurrency(columnIds, 4, async (columnId) => {
        await loadColumn(columnId, generation, true, controller.signal);
      });
    } catch (reason) {
      if (!controller.signal.aborted && generation === generationRef.current) setError(errorMessage(reason));
    } finally {
      if (!controller.signal.aborted && generation === generationRef.current) setLoading(false);
      if (initialLoadControllerRef.current === controller) initialLoadControllerRef.current = null;
    }
  }, [loadColumn, request]);

  useEffect(() => {
    let active = true;
    setLoadingWorkspaces(true);
    void listWorkspaces(request).then((result) => {
      if (!active) return;
      setWorkspaces(result);
      const preferred = result.find((workspace) => workspace.id === requestedWorkspaceId) ?? result[0];
      if (!boardId && preferred) setWorkspaceId(preferred.id);
    }).catch((reason: unknown) => {
      if (active) setError(errorMessage(reason));
    }).finally(() => {
      if (active) setLoadingWorkspaces(false);
    });
    return () => { active = false; };
  }, [boardId, request, requestedWorkspaceId]);

  useEffect(() => {
    if (!workspaceId) {
      setWorkspaceMembers([]);
      return;
    }
    let active = true;
    // Best-effort member lookup to render readable names & avatars on cards and filters.
    void listWorkspaceMembers(request, workspaceId, '', 0).then((result) => {
      if (active) setWorkspaceMembers(result.items);
    }).catch(() => {
      if (active) setWorkspaceMembers([]);
    });
    return () => { active = false; };
  }, [request, workspaceId]);

  useEffect(() => {
    if (boardId) {
      setSelectedTaskId(null);
      setSelectedTaskDeleted(false);
      void loadBoard(boardId);
      return () => {
        reconciliationWorkerTokenRef.current += 1;
        reconciliationRunningRef.current = false;
        queuedSyncRef.current = emptyBoardSyncScopes();
        generationRef.current += 1;
        initialLoadControllerRef.current?.abort();
        reconciliationControllerRef.current?.abort();
      };
    }
    setBoard(null);
    setLoading(false);
    return undefined;
  }, [boardId, loadBoard, sessionGeneration]);

  useEffect(() => {
    if (!workspaceId) return;
    let active = true;
    setLoadingBoards(true);
    void listBoards(request, workspaceId).then((response: BoardPageResponse) => {
      if (!active) return;
      setBoards(response.items);
      if (!boardId && response.items[0]) navigate(`/boards/${response.items[0].id}`, { replace: true });
    }).catch((reason: unknown) => {
      if (active) setError(errorMessage(reason));
    }).finally(() => { if (active) setLoadingBoards(false); });
    return () => { active = false; };
  }, [boardId, navigate, request, workspaceId]);

  const reconcileBoardSnapshot = useCallback(async (scopes: BoardSyncScopes): Promise<'current' | 'partial' | 'superseded'> => {
    const id = boardId;
    const generation = generationRef.current;
    const eventRevision = eventRevisionRef.current;
    if (!id) return 'partial';

    initialLoadControllerRef.current?.abort();
    const controller = new AbortController();
    reconciliationControllerRef.current = controller;
    try {
      const loadedPagesByColumn = new Map<string, number>();
      if (boardRef.current?.id === id) {
        // Complete columns must stay complete as they grow, and columns whose initial load this
        // reconciliation just aborted (loadedPages 0) need the full limit too. Only columns the user
        // deliberately paged keep their loaded page count.
        for (const column of boardRef.current.columns) {
          const fullLoad = column.complete || column.loadedPages === 0;
          loadedPagesByColumn.set(column.id, fullLoad ? MAX_PAGES_PER_COLUMN : column.loadedPages);
        }
      }
      const snapshotResult = await fetchBoardSnapshot(request, id, scopes, controller.signal, loadedPagesByColumn);
      const response = snapshotResult.board;
      if (controller.signal.aborted || generation !== generationRef.current || id !== boardIdRef.current) return 'superseded';

      const columns = [...response.columns].sort((left, right) => left.position - right.position);
      const current = boardRef.current;
      for (const columnId of snapshotResult.requestedColumnIds) {
        columnLoadSequenceRef.current.set(columnId, (columnLoadSequenceRef.current.get(columnId) ?? 0) + 1);
      }

      if (controller.signal.aborted || generation !== generationRef.current || id !== boardIdRef.current) return 'superseded';
      if (eventRevisionRef.current !== eventRevision) return 'superseded';

      const nextColumns: BoardColumn[] = columns.map((column) => {
        const snapshot = snapshotResult.snapshots.get(column.id);
        const previous = current?.columns.find((item) => item.id === column.id);
        if (snapshot) {
          return {
            ...column,
            tasks: snapshot.tasks,
            totalElements: snapshot.page.totalElements,
            totalPages: snapshot.page.totalPages,
            loadedPages: snapshot.nextPage,
            complete: snapshot.complete,
            loading: false,
            error: null,
          };
        }
        if (previous) return { ...previous, ...column };
        return { ...column, tasks: [], totalElements: 0, totalPages: 1, loadedPages: 0, complete: false, loading: false, error: null };
      });
      setBoard({ ...response, columns: nextColumns });
      setLoading(false);
      setWorkspaceId(response.workspaceId);
      setBoards((items) => items.map((item) => item.id === response.id ? { ...item, name: response.name, description: response.description, archived: response.archived, updatedAt: response.updatedAt } : item));
      setSelectedTaskDeleted(false);
      return nextColumns.every((column) => column.complete) ? 'current' : 'partial';
    } finally {
      if (reconciliationControllerRef.current === controller) reconciliationControllerRef.current = null;
    }
  }, [boardId, request]);

  const scheduleBoardSync = useCallback((incoming: BoardSyncScopes) => {
    // A completion captured on another board (e.g. a late move response) must not sync this route.
    if (!boardId || boardId !== boardIdRef.current) return;
    mergeBoardSyncScopes(queuedSyncRef.current, incoming);
    eventRevisionRef.current += 1;

    const structural = incoming.full || incoming.refreshColumns.size > 0;
    if (structural && activeDragRef.current) {
      dragInvalidatedRef.current = true;
      previewRef.current = null;
      setPreviewColumns(null);
    }
    setSyncBlocked(true);
    syncBlockedRef.current = true;
    setNotice('Đang đồng bộ dữ liệu mới…');
    if (connectionStatusRef.current !== 'connected') return;
    if (syncRetryTimerRef.current !== undefined) {
      setNotice('Đang chờ thời điểm thử đồng bộ tiếp theo…');
      return;
    }

    const now = Date.now();
    if (debounceStartedAtRef.current === 0) debounceStartedAtRef.current = now;
    if (syncDebounceTimerRef.current !== undefined) window.clearTimeout(syncDebounceTimerRef.current);
    const debounceWait = Math.min(75, Math.max(0, 250 - (now - debounceStartedAtRef.current)));
    const throttleWait = Math.max(0, 250 - (now - lastSyncBatchStartedAtRef.current));
    const wait = Math.max(debounceWait, throttleWait);
    syncDebounceTimerRef.current = window.setTimeout(() => {
      syncDebounceTimerRef.current = undefined;
      debounceStartedAtRef.current = 0;
      if (reconciliationRunningRef.current) return;
      const workerToken = ++reconciliationWorkerTokenRef.current;
      const workerGeneration = generationRef.current;
      reconciliationRunningRef.current = true;

      void (async () => {
        let iterations = 0;
        let failure: unknown;
        let terminal = false;
        let incompleteSnapshot = false;
        while (iterations < 8) {
          const scopes = queuedSyncRef.current;
          if (!hasBoardSyncWork(scopes)) break;
          queuedSyncRef.current = emptyBoardSyncScopes();
          iterations += 1;
          lastSyncBatchStartedAtRef.current = Date.now();
          try {
            if (scopes.refreshTaskDetail && selectedTaskIdRef.current) setTaskDetailRefreshVersion((version) => version + 1);
            if (scopes.refreshComments && selectedTaskIdRef.current) setCommentsRefreshVersion((version) => version + 1);
            const result = scopes.full || scopes.refreshColumns.size > 0
              ? await reconcileBoardSnapshot(scopes)
              : 'current';
            if (workerToken !== reconciliationWorkerTokenRef.current || workerGeneration !== generationRef.current) break;
            if (result === 'superseded') mergeBoardSyncScopes(queuedSyncRef.current, scopes);
            else if (result === 'partial') {
              incompleteSnapshot = true;
            } else if (scopes.full || scopes.refreshColumns.size > 0) {
              incompleteSnapshot = false;
            }
            failure = undefined;
            break;
          } catch (reason) {
            if (workerToken !== reconciliationWorkerTokenRef.current || workerGeneration !== generationRef.current) break;
            failure = reason;
            setLoading(false);
            if (reason instanceof ApiError && (reason.status === 403 || reason.status === 404)) {
              setBoard(null);
              setSelectedTaskId(null);
              setNotice('Board không còn tồn tại hoặc bạn không có quyền truy cập.');
              setSyncBlocked(true);
              syncBlockedRef.current = true;
              if (workspaceIdRef.current) void listBoards(request, workspaceIdRef.current).then((result) => setBoards(result.items)).catch(() => undefined);
              terminal = true;
              failure = undefined;
              navigate('/boards', { replace: true });
              break;
            }
            mergeBoardSyncScopes(queuedSyncRef.current, scopes);
            break;
          }
        }
        if (workerToken !== reconciliationWorkerTokenRef.current || workerGeneration !== generationRef.current) return;
        reconciliationRunningRef.current = false;
        if (terminal) return;
        if (failure !== undefined) {
          const attempt = retryAttemptRef.current++;
          const retryAfter = failure instanceof ApiError ? failure.problem?.retryAfterSeconds : undefined;
          const delay = retryAfter ? retryAfter * 1_000 : Math.min(30_000, 2_000 * 2 ** Math.min(attempt, 4));
          setSyncBlocked(true);
          syncBlockedRef.current = true;
          setNotice(`${errorMessage(failure)} Đang thử đồng bộ lại.`);
          if (syncRetryTimerRef.current !== undefined) window.clearTimeout(syncRetryTimerRef.current);
          syncRetryTimerRef.current = window.setTimeout(() => {
            syncRetryTimerRef.current = undefined;
            scheduleBoardSync({ ...emptyBoardSyncScopes(), full: true });
          }, delay);
          return;
        }
        const remaining = queuedSyncRef.current;
        if (hasBoardSyncWork(remaining)) {
          scheduleBoardSync(emptyBoardSyncScopes());
          return;
        }
        retryAttemptRef.current = 0;
        if (connectionStatusRef.current === 'connected') {
          setSyncBlocked(false);
          syncBlockedRef.current = false;
          const boardIncomplete = incompleteSnapshot || Boolean(boardRef.current?.columns.some((column) => !column.complete));
          setNotice(boardIncomplete ? `Board chưa tải đủ công việc (tối đa ${MAX_PAGES_PER_COLUMN} trang mỗi lượt). Dùng nút tải tiếp trên từng cột để bật lại kéo thả.` : null);
        }
      })();
    }, wait);
  }, [boardId, navigate, reconcileBoardSnapshot, request]);

  const handleBoardMutation = useCallback((message: BoardMutationMessage) => {
    const now = Date.now();
    const seen = seenEventIdsRef.current;
    if (seen.has(message.eventId)) return;
    seen.set(message.eventId, now);
    for (const [eventId, timestamp] of seen) {
      if (now - timestamp > 300_000) seen.delete(eventId);
    }
    while (seen.size > 1_000) {
      const oldest = seen.keys().next().value as string | undefined;
      if (oldest === undefined) break;
      seen.delete(oldest);
    }

    const refresh = new Set(message.data.refresh);
    if (message.type === 'BOARD_DELETED') {
      eventRevisionRef.current += 1;
      queuedSyncRef.current = emptyBoardSyncScopes();
      setBoard(null);
      setSelectedTaskId(null);
      setSelectedTaskDeleted(false);
      setSyncBlocked(true);
      syncBlockedRef.current = true;
      setNotice('Board đã bị xóa.');
      if (workspaceIdRef.current) void listBoards(request, workspaceIdRef.current).then((result) => setBoards(result.items)).catch(() => undefined);
      navigate('/boards', { replace: true });
      return;
    }

    if (message.type === 'CARD_DELETED' && message.data.taskId) {
      const deletedTaskId = message.data.taskId;
      if (deletedTaskId === selectedTaskId) setSelectedTaskDeleted(true);
      setBoard((current) => current ? {
        ...current,
        columns: current.columns.map((column) => ({
          ...column,
          tasks: column.tasks.filter((task) => task.id !== deletedTaskId),
          totalElements: column.tasks.some((task) => task.id === deletedTaskId) ? Math.max(0, column.totalElements - 1) : column.totalElements,
        })),
      } : current);
    }

    const scopes = emptyBoardSyncScopes();
    scopes.full = refresh.has('BOARD') || refresh.has('COLUMNS') || message.type.startsWith('COLUMN_');
    if (refresh.has('TASK_LISTS')) {
      for (const columnId of message.data.affectedColumnIds) scopes.refreshColumns.add(columnId);
      if (scopes.refreshColumns.size === 0) scopes.full = true;
    }
    const selectedTaskMatches = !message.data.taskId || message.data.taskId === selectedTaskId;
    scopes.refreshTaskDetail = refresh.has('TASK_DETAIL') && selectedTaskMatches;
    scopes.refreshComments = scopes.refreshTaskDetail && message.data.changedFields.includes('comments');
    scheduleBoardSync(scopes);
  }, [navigate, request, scheduleBoardSync, selectedTaskId]);

  const handleConnectionStatus = useCallback((status: BoardConnectionStatus) => {
    connectionStatusRef.current = status;
    setConnectionStatus(status);
    if (status !== 'connected') {
      if (status === 'reconnecting' || status === 'offline') queuedSyncRef.current.full = true;
      setSyncBlocked(true);
      syncBlockedRef.current = true;
      setNotice(status === 'offline' ? 'Mất kết nối mạng · đang hiển thị dữ liệu gần nhất (chỉ đọc).'
        : status === 'error' ? 'Không thể kết nối đồng bộ realtime · đang hiển thị dữ liệu đã tải gần nhất.'
          : 'Đang kết nối đồng bộ board…');
    }
  }, []);

  const handleConnected = useCallback(() => {
    // Events missed while disconnected may include the open task's detail or comments.
    const modalOpen = Boolean(selectedTaskIdRef.current);
    scheduleBoardSync({ ...emptyBoardSyncScopes(), full: true, refreshTaskDetail: modalOpen, refreshComments: modalOpen });
    if (connectionResyncTimerRef.current !== undefined) window.clearTimeout(connectionResyncTimerRef.current);
    connectionResyncTimerRef.current = window.setTimeout(() => {
      connectionResyncTimerRef.current = undefined;
      scheduleBoardSync({ ...emptyBoardSyncScopes(), full: true });
    }, 500);
  }, [scheduleBoardSync]);

  const handleMalformedBoardMessage = useCallback(() => {
    if (malformedResyncTimerRef.current !== undefined) return;
    scheduleBoardSync({ ...emptyBoardSyncScopes(), full: true });
    malformedResyncTimerRef.current = window.setTimeout(() => {
      malformedResyncTimerRef.current = undefined;
    }, 2_000);
  }, [scheduleBoardSync]);

  const realtime = useBoardSync({
    boardId,
    enabled: authReady && Boolean(user),
    sessionGeneration,
    getValidAccessToken,
    onMessage: handleBoardMutation,
    onMalformedMessage: handleMalformedBoardMessage,
    onConnected: handleConnected,
    onStatus: handleConnectionStatus,
  });

  useEffect(() => {
    if (!boardId) return undefined;
    const seenEventIds = seenEventIdsRef.current;
    const reconcileWhenVisible = () => {
      if (document.visibilityState === 'visible') scheduleBoardSync({ ...emptyBoardSyncScopes(), full: true });
    };
    const scheduleNextPeriodic = () => {
      periodicSyncTimerRef.current = window.setTimeout(() => {
        periodicSyncTimerRef.current = undefined;
        reconcileWhenVisible();
        scheduleNextPeriodic();
      }, 30_000 + Math.random() * 5_000);
    };
    document.addEventListener('visibilitychange', reconcileWhenVisible);
    window.addEventListener('focus', reconcileWhenVisible);
    window.addEventListener('pageshow', reconcileWhenVisible);
    scheduleNextPeriodic();
    return () => {
      document.removeEventListener('visibilitychange', reconcileWhenVisible);
      window.removeEventListener('focus', reconcileWhenVisible);
      window.removeEventListener('pageshow', reconcileWhenVisible);
      if (periodicSyncTimerRef.current !== undefined) window.clearTimeout(periodicSyncTimerRef.current);
      if (connectionResyncTimerRef.current !== undefined) window.clearTimeout(connectionResyncTimerRef.current);
      if (malformedResyncTimerRef.current !== undefined) window.clearTimeout(malformedResyncTimerRef.current);
      if (syncRetryTimerRef.current !== undefined) window.clearTimeout(syncRetryTimerRef.current);
      if (syncDebounceTimerRef.current !== undefined) window.clearTimeout(syncDebounceTimerRef.current);
      reconciliationControllerRef.current?.abort();
      periodicSyncTimerRef.current = undefined;
      connectionResyncTimerRef.current = undefined;
      malformedResyncTimerRef.current = undefined;
      syncRetryTimerRef.current = undefined;
      syncDebounceTimerRef.current = undefined;
      debounceStartedAtRef.current = 0;
      retryAttemptRef.current = 0;
      lastSyncBatchStartedAtRef.current = 0;
      queuedSyncRef.current = emptyBoardSyncScopes();
      seenEventIds.clear();
    };
  }, [boardId, scheduleBoardSync]);

  const announce = (message: string) => setNotice(message);

  const onDragOver = (event: DragOverEvent) => {
    const source = event.operation.source?.data as DragData | undefined;
    const target = event.operation.target?.data as DragData | undefined;
    if (source?.kind !== 'task' || !source.taskId) return;
    // Task previews are rendered from React state. Letting OptimisticSortingPlugin also move the
    // card's DOM node across columns makes React remove a node it no longer owns (removeChild crash).
    event.preventDefault();
    if (pending || syncBlocked || !target) return;
    const base = previewRef.current ?? cloneColumns(board?.columns ?? []);
    let beforeTaskId = target.kind === 'task' ? target.taskId : undefined;
    if (target.kind === 'task' && target.taskId) {
      const targetElement = document.getElementById(`task-drop-${target.taskId}`);
      const destinationTasks = base.find((column) => column.id === target.columnId)?.tasks.filter((task) => task.id !== source.taskId) ?? [];
      const targetIndex = destinationTasks.findIndex((task) => task.id === target.taskId);
      const targetRect = targetElement?.getBoundingClientRect();
      if (targetRect && event.operation.position.current.y > targetRect.top + targetRect.height / 2) {
        beforeTaskId = destinationTasks[targetIndex + 1]?.id;
      }
    }
    const moved = moveTaskBefore(base, source.taskId, target.columnId, beforeTaskId);
    if (moved !== base) {
      previewRef.current = moved;
      setPreviewColumns(moved);
    }
  };

  const finishTaskMove = async (taskId: string, original: BoardColumn[], draft: BoardColumn[]) => {
    if (dragInvalidatedRef.current || syncBlockedRef.current || connectionStatusRef.current !== 'connected') {
      previewRef.current = null;
      setPreviewColumns(null);
      setActiveDragName('');
      activeDragRef.current = false;
      dragInvalidatedRef.current = false;
      announce('Board vừa thay đổi hoặc mất kết nối. Dữ liệu đã được tải lại; hãy kéo lại công việc.');
      return;
    }
    const position = locateTask(draft, taskId);
    const previous = locateTask(original, taskId);
    if (!position || !previous || (position.columnId === previous.columnId && position.index === previous.index)) {
      previewRef.current = null;
      setPreviewColumns(null);
      activeDragRef.current = false;
      return;
    }
    const fromColumn = original.find((column) => column.id === previous.columnId);
    const toColumn = draft.find((column) => column.id === position.columnId);
    if (!fromColumn?.complete || !toColumn?.complete) {
      previewRef.current = null;
      setPreviewColumns(null);
      activeDragRef.current = false;
      announce('Cần tải đủ công việc trong hai cột trước khi sắp xếp.');
      return;
    }

    const generation = generationRef.current;
    const startingEventRevision = eventRevisionRef.current;
    const optimistic = draft.map((column) => ({ ...column, loading: false }));
    previewRef.current = null;
    setPreviewColumns(null);
    setBoard((current) => current ? { ...current, columns: optimistic } : current);
    setPending(true);
    setNotice('Đang lưu vị trí…');
    let committed = false;
    try {
      await moveTask(request, taskId, position.columnId, position.index);
      committed = true;
      if (generation === generationRef.current) scheduleBoardSync({ full: false, refreshColumns: new Set([previous.columnId, position.columnId]), refreshTaskDetail: false, refreshComments: false });
      if (generation === generationRef.current) setNotice('Đã lưu vị trí. Đang đồng bộ board…');
    } catch (reason) {
      const uncertain = committed || (reason instanceof ApiError && reason.uncertain);
      const concurrentRemoteChange = eventRevisionRef.current !== startingEventRevision;
      if (generation === generationRef.current && !uncertain && !concurrentRemoteChange) {
        setBoard((current) => current ? { ...current, columns: original } : current);
        setNotice(errorMessage(reason));
      } else if (generation === generationRef.current) {
        setSyncBlocked(true);
        syncBlockedRef.current = true;
        setNotice(committed ? 'Đã lưu nhưng chưa tải được dữ liệu mới. Đang kiểm tra lại…' : 'Chưa xác nhận được kết quả lưu. Đang kiểm tra lại dữ liệu máy chủ…');
      }
      if (generation === generationRef.current) scheduleBoardSync({ full: false, refreshColumns: new Set([previous.columnId, position.columnId]), refreshTaskDetail: false, refreshComments: false });
    } finally {
      if (generation === generationRef.current) setPending(false);
    }
  };

  const onDragEnd = (event: DragEndEvent) => {
    activeDragRef.current = false;
    if (dragInvalidatedRef.current) {
      dragInvalidatedRef.current = false;
      previewRef.current = null;
      setPreviewColumns(null);
      setActiveDragName('');
      return;
    }
    const source = event.operation.source;
    const target = event.operation.target;
    if (!source || event.canceled) {
      previewRef.current = null;
      setPreviewColumns(null);
      setActiveDragName('');
      return;
    }
    const sourceData = source.data as DragData;
    const targetData = target?.data as DragData | undefined;
    if (sourceData.kind === 'task' && sourceData.taskId) {
      const original = board?.columns ?? [];
      let draft = previewRef.current ?? cloneColumns(original);
      if (targetData) {
        let beforeTaskId = targetData.kind === 'task' ? targetData.taskId : undefined;
        if (targetData.kind === 'task' && targetData.taskId) {
          const targetElement = document.getElementById(`task-drop-${targetData.taskId}`);
          const destinationTasks = draft.find((column) => column.id === targetData.columnId)?.tasks.filter((task) => task.id !== sourceData.taskId) ?? [];
          const targetIndex = destinationTasks.findIndex((task) => task.id === targetData.taskId);
          const targetRect = targetElement?.getBoundingClientRect();
          if (targetRect && event.operation.position.current.y > targetRect.top + targetRect.height / 2) {
            beforeTaskId = destinationTasks[targetIndex + 1]?.id;
          }
        }
        draft = moveTaskBefore(draft, sourceData.taskId, targetData.columnId, beforeTaskId);
      }
      setActiveDragName('');
      void finishTaskMove(sourceData.taskId, original, draft);
      return;
    }
    if (sourceData.kind === 'column' && board && isSortable(source)) {
      const original = board.columns;
      // OptimisticSortingPlugin reorders the DOM while dragging, so on drop the target is usually the
      // source column itself. The sortable indexes carry the real start and drop positions.
      const targetColumnId = original[source.index]?.id;
      setActiveDragName('');
      if (source.initialIndex === source.index || !targetColumnId) return;
      const draft = reorderColumnById(original, sourceData.columnId, targetColumnId);
      if (draft === original) return;
      const toPosition = draft.findIndex((column) => column.id === sourceData.columnId);
      const generation = generationRef.current;
      setBoard((current) => current ? { ...current, columns: draft } : current);
      setPending(true);
      const startingEventRevision = eventRevisionRef.current;
      setNotice('Đang lưu thứ tự cột…');
      void reorderColumn(request, sourceData.columnId, toPosition).then((response: ColumnResponse[]) => {
        if (generation !== generationRef.current) return;
        if (eventRevisionRef.current === startingEventRevision) {
          setBoard((current) => current ? { ...current, columns: applyColumnOrder(current.columns, response) } : current);
        }
        scheduleBoardSync({ ...emptyBoardSyncScopes(), full: true });
        setNotice('Đã lưu thứ tự cột. Đang đồng bộ board…');
      }).catch(async (reason: unknown) => {
        if (generation !== generationRef.current) return;
        if (reason instanceof ApiError && !reason.uncertain && eventRevisionRef.current === startingEventRevision) setBoard((current) => current ? { ...current, columns: original } : current);
        setNotice(errorMessage(reason));
        scheduleBoardSync({ ...emptyBoardSyncScopes(), full: true });
      }).finally(() => { if (generation === generationRef.current) setPending(false); });
    }
  };

  const handleDragStart = (event: DragStartEvent) => {
    if (syncBlockedRef.current || connectionStatusRef.current !== 'connected') return;
    const source = event.operation.source?.data as DragData | undefined;
    activeDragRef.current = true;
    dragInvalidatedRef.current = false;
    if (source?.kind === 'task' && source.taskId) {
      const task = board?.columns.flatMap((column) => column.tasks).find((item) => item.id === source.taskId);
      setActiveDragName(task?.title ?? 'Công việc');
      previewRef.current = cloneColumns(board?.columns ?? []);
      setPreviewColumns(previewRef.current);
    } else if (source?.kind === 'column') {
      setActiveDragName(board?.columns.find((column) => column.id === source.columnId)?.name ?? 'Cột');
    }
  };

  const handleWorkspaceChange = (nextWorkspaceId: string) => {
    setWorkspaceId(nextWorkspaceId);
    setBoards([]);
    setBoard(null);
    navigate('/boards', { replace: true });
  };

  const moveTaskFromMenu = (taskId: string, destinationColumnId: string) => {
    const original = board?.columns;
    if (!original) return;
    const draft = moveTaskBefore(original, taskId, destinationColumnId);
    void finishTaskMove(taskId, original, draft);
  };

  const openTaskModal = useCallback((taskId: string, trigger?: HTMLElement | null) => {
    modalReturnFocusRef.current = trigger ?? (document.activeElement instanceof HTMLElement ? document.activeElement : null);
    setSelectedTaskDeleted(false);
    setSelectedTaskId(taskId);
  }, []);

  const closeTaskModal = useCallback(() => {
    setSelectedTaskId(null);
    const returnTarget = modalReturnFocusRef.current;
    modalReturnFocusRef.current = null;
    if (returnTarget && returnTarget.isConnected) {
      requestAnimationFrame(() => returnTarget.focus());
    }
  }, []);

  const activeColumns = useMemo(() => {
    const term = filterText.trim().toLocaleLowerCase();
    return displayColumns.map((column) => ({
      ...column,
      visibleTasks: column.tasks.filter((task) =>
        (!term || task.title.toLocaleLowerCase().includes(term) || task.taskKey.toLocaleLowerCase().includes(term)) &&
        (!assigneeFilter || task.assigneeId === assigneeFilter || (assigneeFilter === 'unassigned' && !task.assigneeId)) &&
        (!priorityFilter || task.priority === priorityFilter),
      ),
    }));
  }, [assigneeFilter, displayColumns, filterText, priorityFilter]);

  const boardMetrics = useMemo(() => {
    if (!board) return null;
    let totalLoaded = 0;
    let totalReported = 0;
    let doneCount = 0;
    let overdueCount = 0;
    let visibleCount = 0;
    const now = Date.now();
    for (const col of activeColumns) {
      totalLoaded += col.tasks.length;
      totalReported += Math.max(col.tasks.length, col.totalElements);
      visibleCount += col.visibleTasks.length;
      const isDoneCol = col.statusCategory === 'DONE';
      for (const task of col.tasks) {
        if (isDoneCol || task.statusCategory === 'DONE') {
          doneCount += 1;
        } else if (task.dueDate && new Date(task.dueDate).getTime() < now) {
          overdueCount += 1;
        }
      }
    }
    const percent = totalLoaded > 0 ? Math.round((doneCount / totalLoaded) * 100) : 0;
    return { totalLoaded, totalReported, doneCount, overdueCount, visibleCount, percent };
  }, [activeColumns, board]);

  const clearFilters = () => {
    setFilterText('');
    setAssigneeFilter('');
    setPriorityFilter('');
  };

  const handleTaskSaved = (updated: TaskDetail) => {
    const current = boardRef.current;
    if (!current) return;
    const location = locateTask(current.columns, updated.id);
    if (!location || location.columnId !== updated.columnId) {
      const affected = new Set([location?.columnId, updated.columnId].filter((id): id is string => Boolean(id)));
      scheduleBoardSync({
        full: false,
        refreshColumns: affected,
        refreshTaskDetail: false,
        refreshComments: false,
      });
      return;
    }
    setBoard((value) => value ? {
      ...value,
      columns: value.columns.map((column) => ({
        ...column,
        tasks: column.tasks.map((task) => task.id === updated.id ? {
          ...task,
          title: updated.title,
          priority: updated.priority,
          assigneeId: updated.assigneeId,
          dueDate: updated.dueDate,
          statusCategory: updated.statusCategory,
          updatedAt: updated.updatedAt,
        } : task),
      })),
    } : value);
  };

  const isAssignedToMeActive = Boolean(user?.id && assigneeFilter === user.id);

  // Unified contextual sync bar — only displayed when action or attention is needed
  const showUnifiedStatusBar = Boolean(
    board && (
      notice ||
      syncBlocked ||
      connectionStatus === 'offline' ||
      connectionStatus === 'error' ||
      connectionStatus === 'reconnecting'
    ),
  );

  const unifiedStatusTone =
    connectionStatus === 'error'
      ? 'border-rose-200/90 bg-rose-50/90 text-rose-900'
      : syncBlocked || connectionStatus === 'offline' || connectionStatus === 'reconnecting'
        ? 'border-amber-200/90 bg-amber-50/95 text-amber-950'
        : 'border-indigo-200/90 bg-indigo-50/90 text-indigo-950';

  const unifiedStatusMessage =
    notice ??
    (connectionStatus === 'offline'
      ? 'Mất kết nối mạng · đang hiển thị dữ liệu gần nhất (chỉ đọc).'
      : connectionStatus === 'error'
        ? 'Không thể kết nối đồng bộ realtime · đang hiển thị dữ liệu đã tải gần nhất.'
        : connectionStatus === 'reconnecting'
          ? 'Đang kết nối lại realtime · dữ liệu đang chờ đối soát.'
          : 'Đang đồng bộ dữ liệu board…');

  return (
    <main className="min-h-screen overflow-x-clip bg-[#f4f6fa] text-slate-900">
      <BoardTopBar
        user={user}
        workspaces={workspaces}
        workspaceId={workspaceId}
        boards={boards}
        boardId={boardId}
        currentBoardArchived={board?.archived}
        connectionStatus={connectionStatus}
        syncBlocked={syncBlocked}
        onWorkspaceChange={handleWorkspaceChange}
        onBoardChange={(nextBoardId) => navigate(`/boards/${nextBoardId}`)}
        onSelectTask={(taskId) => openTaskModal(taskId)}
        onRetrySync={() => scheduleBoardSync({ ...emptyBoardSyncScopes(), full: true })}
        onRetryConnection={realtime.retry}
        onSignOut={signOut}
      />

      <div className="mx-auto max-w-[1600px] px-3 pb-24 pt-4 sm:px-6 sm:pt-5 lg:px-8">
        {error && (
          <div className="mb-4 flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-900" role="alert">
            <div className="flex min-w-0 items-center gap-2.5">
              <AlertCircleIcon className="size-4 shrink-0 text-rose-600" />
              <span className="min-w-0">{error}</span>
            </div>
            <button
              type="button"
              className="inline-flex min-h-9 shrink-0 items-center gap-1.5 rounded-xl bg-white px-3 py-1.5 text-xs font-semibold text-rose-700 ring-1 ring-inset ring-rose-200 transition hover:bg-rose-100/70 focus:outline-none focus-visible:ring-2 focus-visible:ring-rose-500"
              onClick={() => boardId ? void loadBoard(boardId) : window.location.reload()}
            >
              <RefreshIcon className="size-3.5" />
              <span>Thử tải lại</span>
            </button>
          </div>
        )}

        {/* Empty Workspace / Empty Board States with clear next steps */}
        {!boardId && !error && (
          loadingWorkspaces || loadingBoards ? (
            <div className="grid min-h-72 place-items-center rounded-2xl border border-slate-200/80 bg-white/80 p-8" role="status">
              <div className="flex items-center gap-2.5 text-sm font-medium text-slate-600">
                <LoadingSpinner className="size-5 text-indigo-600" />
                <span>{loadingWorkspaces ? 'Đang tải danh sách workspace…' : 'Đang tải danh sách board…'}</span>
              </div>
            </div>
          ) : workspaces.length === 0 ? (
            <EmptyStatePanel
              icon={<LayoutGridIcon className="size-6" />}
              eyebrow="Workspace"
              title="Bạn chưa thuộc workspace nào"
              description="Tạo workspace đầu tiên cho nhóm phát triển của bạn, hoặc mở liên kết lời mời từ quản trị viên để tham gia dự án."
              actions={(
                <>
                  <Link
                    to="/workspaces/new"
                    className="inline-flex min-h-11 items-center gap-2 rounded-xl bg-indigo-600 px-5 py-2.5 text-sm font-semibold text-white shadow-sm shadow-indigo-600/20 transition hover:bg-indigo-700 focus:outline-none focus-visible:ring-4 focus-visible:ring-indigo-500/30"
                  >
                    <PlusIcon className="size-4" />
                    <span>Tạo workspace mới</span>
                  </Link>
                  <Link
                    to="/workspaces"
                    className="inline-flex min-h-11 items-center gap-2 rounded-xl border border-slate-300 bg-white px-4 py-2.5 text-sm font-semibold text-slate-700 transition hover:bg-slate-50 focus:outline-none focus-visible:ring-4 focus-visible:ring-indigo-500/20"
                  >
                    <span>Mở trang quản lý workspace</span>
                  </Link>
                </>
              )}
            />
          ) : (
            <EmptyStatePanel
              icon={<KanbanIcon className="size-6" />}
              eyebrow={activeWorkspace ? `Workspace · ${activeWorkspace.name}` : 'Workspace'}
              title="Workspace này chưa có board nào"
              description={(
                <>
                  {activeWorkspace && (
                    <span className="mb-2 inline-flex items-center gap-1.5">
                      <span>Vai trò của bạn:</span>
                      <RoleBadge role={activeWorkspace.role} />
                    </span>
                  )}
                  <span className="block">
                    Chuyển sang workspace khác đã có board hoặc đợi quản trị viên tạo board mới trong workspace này.
                  </span>
                </>
              )}
              actions={(
                <>
                  <Link
                    to="/workspaces"
                    className="inline-flex min-h-11 items-center gap-2 rounded-xl bg-indigo-600 px-5 py-2.5 text-sm font-semibold text-white shadow-sm shadow-indigo-600/20 transition hover:bg-indigo-700 focus:outline-none focus-visible:ring-4 focus-visible:ring-indigo-500/30"
                  >
                    <LayoutGridIcon className="size-4" />
                    <span>Chọn workspace khác</span>
                  </Link>
                  <Link
                    to="/workspaces/new"
                    className="inline-flex min-h-11 items-center gap-2 rounded-xl border border-slate-300 bg-white px-4 py-2.5 text-sm font-semibold text-slate-700 transition hover:bg-slate-50 focus:outline-none focus-visible:ring-4 focus-visible:ring-indigo-500/20"
                  >
                    <PlusIcon className="size-4" />
                    <span>Tạo workspace mới</span>
                  </Link>
                </>
              )}
            />
          )
        )}

        {/* Board Loading Skeleton */}
        {boardId && loading && !board && (
          <div className="space-y-4" role="status" aria-label="Đang tải board và công việc">
            <div className="h-16 animate-pulse rounded-2xl border border-slate-200/80 bg-white/80 motion-reduce:animate-none" />
            <div className="flex gap-4 overflow-hidden">
              {[0, 1, 2, 3].map((col) => (
                <div key={col} className="h-96 w-[316px] shrink-0 animate-pulse rounded-2xl border border-slate-200/80 bg-white/70 motion-reduce:animate-none" />
              ))}
            </div>
          </div>
        )}

        {board && (
          <>
            {/* Compact Board Title & Sprint Progress Header */}
            <section className="mb-3.5 flex flex-col gap-2.5 sm:mb-4 lg:flex-row lg:items-end lg:justify-between">
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-2">
                  <h1 className="text-lg font-extrabold leading-snug tracking-tight text-slate-950 sm:text-2xl">
                    {board.name}
                  </h1>
                  {board.archived && (
                    <Badge tone="warning">
                      <ArchiveIcon className="size-3" />
                      <span>Đã lưu trữ · Chỉ xem</span>
                    </Badge>
                  )}
                  {activeWorkspace && (
                    <RoleBadge role={activeWorkspace.role} className="xl:hidden" />
                  )}
                </div>
                {board.description && (
                  <p className="mt-1 line-clamp-2 max-w-3xl text-xs leading-5 text-slate-600 sm:text-sm">
                    {board.description}
                  </p>
                )}
              </div>

              {boardMetrics && (
                <div className="flex flex-wrap items-center gap-2 text-xs">
                  <div
                    className="inline-flex items-center gap-2 rounded-xl border border-slate-200/90 bg-white px-3 py-1.5 shadow-2xs"
                    title={`Hoàn thành ${boardMetrics.doneCount} trên ${boardMetrics.totalLoaded} công việc đã tải`}
                  >
                    <span className="tabular-nums font-bold text-slate-900">
                      {boardMetrics.doneCount}/{boardMetrics.totalLoaded}
                    </span>
                    <span className="text-slate-500">hoàn thành</span>
                    <span className="h-1.5 w-12 overflow-hidden rounded-full bg-slate-100" aria-hidden="true">
                      <span
                        className="block h-full rounded-full bg-emerald-500 transition-all"
                        style={{ width: `${boardMetrics.percent}%` }}
                      />
                    </span>
                  </div>
                  {boardMetrics.overdueCount > 0 && (
                    <Badge tone="danger" className="py-1 text-xs">
                      <span className="tabular-nums">{boardMetrics.overdueCount} quá hạn</span>
                    </Badge>
                  )}
                  <span className="tabular-nums hidden rounded-xl border border-slate-200/90 bg-white px-3 py-1.5 font-semibold text-slate-600 shadow-2xs sm:inline-flex">
                    {board.columns.length} cột · {boardMetrics.totalLoaded} công việc
                  </span>
                </div>
              )}
            </section>

            {/* Compact Responsive Filter Toolbar */}
            <section
              className="mb-3.5 rounded-2xl border border-slate-200/90 bg-white p-2 shadow-[0_1px_2px_rgba(15,23,42,0.03)] sm:mb-4 sm:p-2.5"
              aria-label="Bộ lọc công việc"
            >
              <div className="flex flex-col gap-2 lg:flex-row lg:items-center">
                {/* Search + Mobile Filter Toggle */}
                <div className="flex min-w-0 flex-1 items-center gap-2">
                  <div className="relative min-w-0 flex-1">
                    <label className="sr-only" htmlFor="board-task-search">Tìm công việc theo mã hoặc tiêu đề</label>
                    <SearchIcon className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-slate-400" />
                    <input
                      id="board-task-search"
                      type="search"
                      className="min-h-10 w-full rounded-xl border border-slate-200/90 bg-slate-50/80 py-2 pl-9 pr-8 text-sm text-slate-900 outline-none transition placeholder:text-slate-400 focus:border-indigo-500 focus:bg-white focus:ring-4 focus:ring-indigo-500/15"
                      placeholder="Tìm theo mã (T-048) hoặc tiêu đề…"
                      value={filterText}
                      onChange={(event) => setFilterText(event.target.value)}
                    />
                    {filterText && (
                      <button
                        type="button"
                        onClick={() => setFilterText('')}
                        aria-label="Xóa từ khóa tìm kiếm"
                        className="absolute right-1.5 top-1/2 grid size-7 -translate-y-1/2 place-items-center rounded-lg text-slate-400 hover:bg-slate-200/70 hover:text-slate-700 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500"
                      >
                        <XIcon className="size-3.5" />
                      </button>
                    )}
                  </div>

                  {/* Mobile collapsible filters button */}
                  <button
                    type="button"
                    onClick={() => setMobileFiltersOpen((open) => !open)}
                    aria-expanded={mobileFiltersOpen}
                    aria-controls="board-secondary-filters"
                    className={clsx(
                      'inline-flex min-h-10 shrink-0 items-center gap-1.5 rounded-xl border px-3 text-xs font-semibold transition focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 md:hidden',
                      mobileFiltersOpen || assigneeFilter || priorityFilter
                        ? 'border-indigo-300 bg-indigo-50 text-indigo-700'
                        : 'border-slate-200 bg-slate-50 text-slate-700 hover:bg-slate-100',
                    )}
                  >
                    <FilterIcon className="size-3.5" />
                    <span>Lọc</span>
                    {(assigneeFilter || priorityFilter) && (
                      <span className="tabular-nums grid size-4 place-items-center rounded-full bg-indigo-600 text-[10px] font-bold text-white">
                        {[assigneeFilter, priorityFilter].filter(Boolean).length}
                      </span>
                    )}
                  </button>
                </div>

                {/* Secondary Filters: always visible on md+, collapsible on mobile so Kanban starts above fold */}
                <div
                  id="board-secondary-filters"
                  className={clsx(
                    'flex-wrap items-center gap-2 md:flex',
                    mobileFiltersOpen ? 'grid grid-cols-1 pt-1 sm:grid-cols-2' : 'hidden',
                  )}
                >
                  {user && (
                    <button
                      type="button"
                      aria-pressed={isAssignedToMeActive}
                      onClick={() => setAssigneeFilter(isAssignedToMeActive ? '' : user.id)}
                      className={clsx(
                        'inline-flex min-h-10 items-center justify-center gap-1.5 rounded-xl border px-3 text-xs font-semibold transition focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500',
                        isAssignedToMeActive
                          ? 'border-indigo-300 bg-indigo-50 text-indigo-700'
                          : 'border-slate-200/90 bg-slate-50/80 text-slate-700 hover:border-slate-300 hover:bg-white',
                      )}
                    >
                      <UserIcon className="size-3.5" />
                      <span>Việc của tôi</span>
                    </button>
                  )}

                  <label className="min-w-0">
                    <span className="sr-only">Lọc theo người phụ trách</span>
                    <select
                      className="min-h-10 w-full rounded-xl border border-slate-200/90 bg-slate-50/80 px-3 py-2 text-xs font-medium text-slate-800 transition hover:border-slate-300 hover:bg-white focus:border-indigo-500 focus:bg-white focus:outline-none focus:ring-4 focus:ring-indigo-500/15 md:w-48"
                      value={assigneeFilter}
                      onChange={(event) => setAssigneeFilter(event.target.value)}
                    >
                      <option value="">Mọi người phụ trách</option>
                      <option value="unassigned">Chưa giao</option>
                      {allAssignees.map((id) => {
                        const m = membersById.get(id);
                        const label = id === user?.id
                          ? `${m?.fullName ? `${m.fullName} (Tôi)` : 'Được giao cho tôi'}`
                          : m?.fullName || `Thành viên ${id.slice(0, 8)}`;
                        return <option key={id} value={id}>{label}</option>;
                      })}
                    </select>
                  </label>

                  <label className="min-w-0">
                    <span className="sr-only">Lọc theo độ ưu tiên</span>
                    <select
                      className="min-h-10 w-full rounded-xl border border-slate-200/90 bg-slate-50/80 px-3 py-2 text-xs font-medium text-slate-800 transition hover:border-slate-300 hover:bg-white focus:border-indigo-500 focus:bg-white focus:outline-none focus:ring-4 focus:ring-indigo-500/15 md:w-40"
                      value={priorityFilter}
                      onChange={(event) => setPriorityFilter(event.target.value)}
                    >
                      <option value="">Mọi độ ưu tiên</option>
                      {PRIORITIES.map((priority) => (
                        <option key={priority} value={priority}>{PRIORITY_LABEL[priority]}</option>
                      ))}
                    </select>
                  </label>

                  {filtersActive && (
                    <button
                      type="button"
                      onClick={clearFilters}
                      className="inline-flex min-h-10 items-center justify-center gap-1 rounded-xl px-3 text-xs font-semibold text-indigo-700 transition hover:bg-indigo-50 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500"
                    >
                      <XIcon className="size-3.5" />
                      <span>Xóa lọc ({activeFilterCount})</span>
                    </button>
                  )}
                </div>
              </div>

              {(filtersActive || !allTasksComplete) && (
                <div className="mt-2 flex flex-wrap items-center gap-x-4 gap-y-1 border-t border-slate-100 px-1 pt-2 text-[11px] font-medium">
                  {filtersActive && boardMetrics && (
                    <span className="text-indigo-700">
                      Đang hiển thị <strong className="tabular-nums">{boardMetrics.visibleCount}/{boardMetrics.totalLoaded}</strong> công việc khớp lọc · kéo thả tạm khóa khi đang lọc.
                    </span>
                  )}
                  {!allTasksComplete && (
                    <span className="text-amber-800">
                      Một số cột chưa tải hết công việc; dùng nút “Tải tiếp” trên cột để xem toàn board.
                    </span>
                  )}
                </div>
              )}
            </section>

            {/* Unified Contextual Sync / Offline / Retry Bar (zero duplicate banners) */}
            {showUnifiedStatusBar && (
              <div
                className={clsx(
                  'mb-3.5 flex flex-wrap items-center justify-between gap-2.5 rounded-xl border px-3.5 py-2.5 text-xs font-medium sm:mb-4 sm:text-sm',
                  unifiedStatusTone,
                )}
                role="status"
                aria-live="polite"
              >
                <div className="flex min-w-0 items-center gap-2">
                  {connectionStatus === 'error' ? (
                    <AlertCircleIcon className="size-4 shrink-0 text-rose-600" />
                  ) : syncBlocked || connectionStatus === 'offline' || connectionStatus === 'reconnecting' ? (
                    <AlertTriangleIcon className="size-4 shrink-0 text-amber-600" />
                  ) : (
                    <InfoIcon className="size-4 shrink-0 text-indigo-600" />
                  )}
                  <span className="min-w-0 leading-5">{unifiedStatusMessage}</span>
                </div>
                <div className="flex shrink-0 items-center gap-2">
                  {syncBlocked && connectionStatus === 'connected' && (
                    <button
                      type="button"
                      onClick={() => scheduleBoardSync({ ...emptyBoardSyncScopes(), full: true })}
                      className="inline-flex min-h-8 items-center gap-1.5 rounded-lg bg-white px-3 py-1 text-xs font-semibold text-amber-900 ring-1 ring-inset ring-amber-300/90 transition hover:bg-amber-100/70 focus:outline-none focus-visible:ring-2 focus-visible:ring-amber-500"
                    >
                      <RefreshIcon className="size-3.5" />
                      <span>Thử đồng bộ</span>
                    </button>
                  )}
                  {(connectionStatus === 'error' || connectionStatus === 'offline') && (
                    <button
                      type="button"
                      onClick={realtime.retry}
                      className="inline-flex min-h-8 items-center gap-1.5 rounded-lg bg-white px-3 py-1 text-xs font-semibold text-slate-900 ring-1 ring-inset ring-slate-300 transition hover:bg-slate-50 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500"
                    >
                      <RefreshIcon className="size-3.5" />
                      <span>Kết nối lại</span>
                    </button>
                  )}
                </div>
              </div>
            )}

            {/* Screen-reader live region for healthy connection state & drag announcements */}
            <p className="sr-only" role="status" aria-live="polite">
              {connectionStatus === 'connected' && !syncBlocked ? 'Đồng bộ realtime đang hoạt động. Dữ liệu đã đối soát.' : ''}
            </p>

            {board.columns.length === 0 ? (
              <EmptyStatePanel
                icon={<KanbanIcon className="size-6" />}
                title="Board này chưa có cột nào"
                description="Khi board có các cột trạng thái (Cần làm, Đang làm, Hoàn thành…), công việc sẽ xuất hiện tại đây."
              />
            ) : (
              /* Only the Kanban lane track scrolls horizontally when needed */
              <DragDropProvider<DragData> onDragStart={handleDragStart} onDragOver={onDragOver} onDragEnd={onDragEnd}>
                <div
                  className="devflow-scroll -mx-3 flex min-h-[60vh] snap-x snap-proximity items-start gap-3.5 overflow-x-auto px-3 pb-6 sm:mx-0 sm:gap-4 sm:px-0"
                  aria-label="Kanban board"
                >
                  {activeColumns.map(({ visibleTasks, ...column }, columnIndex) => (
                    <BoardColumnView
                      key={column.id}
                      column={column}
                      visibleTasks={visibleTasks}
                      availableColumns={board.columns}
                      membersById={membersById}
                      currentUserId={user?.id}
                      index={columnIndex}
                      activeFilters={filtersActive}
                      canReorder={!board.archived && !pending && !syncBlocked && connectionStatus === 'connected'}
                      canMoveCards={!board.archived && !pending && !syncBlocked && connectionStatus === 'connected' && !filtersActive && column.complete}
                      onMoveTask={moveTaskFromMenu}
                      onOpenTask={openTaskModal}
                      onClearFilters={clearFilters}
                      onRetry={() => void loadColumn(column.id, generationRef.current)}
                      onLoadMore={() => void loadColumn(column.id, generationRef.current, false)}
                    />
                  ))}
                </div>
              </DragDropProvider>
            )}

            <p className="sr-only" aria-live="assertive">
              {activeDragName ? `Đang di chuyển ${activeDragName}` : notice ?? ''}
            </p>

            {selectedTaskId && (
              <Suspense
                fallback={(
                  <div className="fixed inset-0 z-50 grid place-items-center bg-slate-950/45 p-4 backdrop-blur-xs" role="status">
                    <div className="inline-flex items-center gap-2.5 rounded-2xl bg-white px-5 py-3.5 text-sm font-medium text-slate-700 shadow-xl">
                      <LoadingSpinner className="size-4 text-indigo-600" />
                      <span>Đang mở chi tiết công việc…</span>
                    </div>
                  </div>
                )}
              >
                <TaskDetailModal
                  key={`${board.id}:${selectedTaskId}`}
                  taskId={selectedTaskId}
                  canEdit={!board.archived && !pending && !syncBlocked && connectionStatus === 'connected'}
                  detailRefreshVersion={taskDetailRefreshVersion}
                  commentsRefreshVersion={commentsRefreshVersion}
                  remoteDeleted={selectedTaskDeleted}
                  onClose={closeTaskModal}
                  onSaved={handleTaskSaved}
                />
              </Suspense>
            )}
          </>
        )}
      </div>
    </main>
  );
}
