import { lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { DragDropProvider } from '@dnd-kit/react';
import type { DragEndEvent, DragOverEvent, DragStartEvent } from '@dnd-kit/react';
import { isSortable, useSortable } from '@dnd-kit/react/sortable';
import { useDroppable } from '@dnd-kit/react';
import { applyColumnOrder, cloneColumns, locateTask, moveTaskBefore, reorderColumnById } from '../board/mutations';
import { getBoard, listBoards, listWorkspaces, moveTask, reorderColumn } from '../api/boardApi';
import { useAuth } from '../auth/AuthProvider';
import { useBoardSync, type BoardConnectionStatus } from '../hooks/useBoardSync';
import { emptyBoardSyncScopes, fetchBoardSnapshot, fetchEveryTaskPage, hasBoardSyncWork, mapWithConcurrency, MAX_PAGES_PER_COLUMN, mergeBoardSyncScopes, type BoardSyncScopes } from '../board/boardSyncCoordinator';
import type { BoardMutationMessage } from '../realtime/boardMutation';
const TaskDetailModal = lazy(() => import('../components/board/TaskDetailModal').then((module) => ({ default: module.TaskDetailModal })));
import { ApiError, type BoardColumn, type BoardPageResponse, type BoardResponse, type BoardState, type BoardSummary, type ColumnResponse, type TaskListItem, type TaskPriority, type WorkspaceSummary } from '../types/board';

const PRIORITIES: TaskPriority[] = ['LOW', 'MEDIUM', 'HIGH', 'URGENT'];
const PRIORITY_LABEL: Record<TaskPriority, string> = { LOW: 'Thấp', MEDIUM: 'Trung bình', HIGH: 'Cao', URGENT: 'Khẩn cấp' };

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
  const { user, ready: authReady, request, signOut, getValidAccessToken, sessionGeneration } = useAuth();
  const [workspaces, setWorkspaces] = useState<WorkspaceSummary[]>([]);
  const [workspaceId, setWorkspaceId] = useState('');
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
  const filtersActive = Boolean(filterText.trim() || assigneeFilter || priorityFilter);
  const allTasksComplete = Boolean(board?.columns.every((column) => column.complete));
  const allAssignees = useMemo(() => [...new Set((board?.columns ?? []).flatMap((column) => column.tasks.map((task) => task.assigneeId).filter((id): id is string => Boolean(id))))].sort(), [board]);
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
      if (!result.complete) setNotice(`Lượt tải cột dừng ở giới hạn ${MAX_PAGES_PER_COLUMN} trang. Dùng nút tải thêm để tiếp tục.`);
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
    void listWorkspaces(request).then((result) => {
      if (!active) return;
      setWorkspaces(result);
      if (!boardId && result[0]) setWorkspaceId(result[0].id);
    }).catch((reason: unknown) => {
      if (active) setError(errorMessage(reason));
    });
    return () => { active = false; };
  }, [boardId, request]);

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
          setNotice(boardIncomplete ? `Board chưa tải đủ task (mỗi lượt tối đa ${MAX_PAGES_PER_COLUMN} trang mỗi cột). Tải thêm để xem toàn bộ task; các cột chưa tải đủ không thể kéo thả.` : null);
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
      setNotice(status === 'offline' ? 'Mất kết nối mạng. Board đang ở trạng thái chỉ đọc.'
        : status === 'error' ? 'Không thể kết nối đồng bộ board. Hãy thử kết nối lại.'
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
      announce('Board vừa thay đổi hoặc mất kết nối. Dữ liệu đã được tải lại; hãy kéo lại task.');
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

  const activeColumns = useMemo(() => displayColumns.map((column) => ({
    ...column,
    visibleTasks: column.tasks.filter((task) =>
      task.title.toLocaleLowerCase().includes(filterText.trim().toLocaleLowerCase()) &&
      (!assigneeFilter || task.assigneeId === assigneeFilter || (assigneeFilter === 'unassigned' && !task.assigneeId)) &&
      (!priorityFilter || task.priority === priorityFilter),
    ),
  })), [assigneeFilter, displayColumns, filterText, priorityFilter]);

  const clearFilters = () => { setFilterText(''); setAssigneeFilter(''); setPriorityFilter(''); };

  const handleTaskSaved = (updated: import('../types/board').TaskDetail) => {
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

  return (
    <main className="min-h-screen bg-[#f6f7fb] text-slate-900">
      <header className="sticky top-0 z-30 border-b border-slate-200/80 bg-white/90 backdrop-blur-xl">
        <div className="mx-auto flex max-w-[1600px] flex-wrap items-center gap-3 px-4 py-3 sm:px-6 lg:px-8">
          <a href="/boards" className="mr-2 flex items-center gap-2 font-bold tracking-tight text-slate-900" aria-label="DevFlow home">
            <span className="grid size-9 place-items-center rounded-xl bg-indigo-600 text-white">D</span> DevFlow
          </a>
          {workspaces.length > 0 && <label className="sr-only" htmlFor="workspace-picker">Workspace</label>}
          {workspaces.length > 0 && <select id="workspace-picker" className="max-w-48 rounded-lg border border-slate-200 bg-slate-50 px-3 py-2 text-sm" value={workspaceId} onChange={(event) => handleWorkspaceChange(event.target.value)}>
            {workspaces.map((workspace) => <option key={workspace.id} value={workspace.id}>{workspace.name}</option>)}
          </select>}
          {boards.length > 0 && <label className="sr-only" htmlFor="board-picker">Board</label>}
          {boards.length > 0 && <select id="board-picker" className="max-w-56 rounded-lg border border-slate-200 bg-slate-50 px-3 py-2 text-sm" value={boardId ?? ''} onChange={(event) => navigate(`/boards/${event.target.value}`)}>
            <option value="" disabled>Chọn board</option>
            {boards.map((item) => <option key={item.id} value={item.id}>{item.name}{item.archived ? ' · Đã lưu trữ' : ''}</option>)}
          </select>}
          <div className="ml-auto flex items-center gap-3 text-sm">
            <span className="hidden text-slate-500 sm:inline">{user?.fullName || user?.email}</span>
            <button className="rounded-lg px-3 py-2 text-slate-600 transition hover:bg-slate-100" onClick={signOut}>Đăng xuất</button>
          </div>
        </div>
      </header>

      <div className="mx-auto max-w-[1600px] px-4 py-8 sm:px-6 lg:px-8">
        {error && <div className="mb-6 flex items-center justify-between gap-4 rounded-2xl border border-rose-200 bg-rose-50 p-4 text-sm text-rose-800" role="alert"><span>{error}</span><button className="font-semibold underline" onClick={() => boardId && void loadBoard(boardId)}>Thử tải lại</button></div>}
        {!boardId && !error && <section className="rounded-3xl border border-slate-200 bg-white p-8 text-center shadow-sm">
          <p className="text-sm font-semibold text-indigo-600">WORKSPACE</p>
          <h1 className="mt-2 text-2xl font-bold">{loadingBoards ? 'Đang tải board…' : workspaces.length === 0 ? 'Bạn chưa có workspace' : 'Chưa có board trong workspace này'}</h1>
          <p className="mt-2 text-sm text-slate-500">{workspaces.length === 0 ? 'Hãy tham gia workspace trước khi quản lý công việc.' : 'Board sẽ xuất hiện tại đây khi workspace có board được cấp quyền.'}</p>
        </section>}
        {boardId && loading && !board && <div className="grid min-h-72 place-items-center text-sm text-slate-500" role="status">Đang tải board và công việc…</div>}
        {board && <>
          <div className="mb-7 flex flex-wrap items-end justify-between gap-5">
            <div>
              <p className="text-xs font-bold uppercase tracking-[0.2em] text-indigo-600">Workspace board</p>
              <h1 className="mt-2 text-3xl font-bold tracking-tight text-slate-950 sm:text-4xl">{board.name}</h1>
              {board.description && <p className="mt-2 max-w-2xl text-sm text-slate-500">{board.description}</p>}
              {board.archived && <span className="mt-3 inline-flex rounded-full bg-amber-100 px-3 py-1 text-xs font-semibold text-amber-800">Board đã lưu trữ · chỉ xem</span>}
            </div>
            <div className="flex items-center gap-2 text-sm text-slate-500">
              <span className="rounded-lg bg-white px-3 py-2 shadow-sm">{board.columns.length} cột</span>
              <span className="rounded-lg bg-white px-3 py-2 shadow-sm">{board.columns.reduce((sum, column) => sum + column.tasks.length, 0)} công việc đã tải</span>
            </div>
          </div>

          <section className="mb-5 rounded-2xl border border-slate-200 bg-white p-3 shadow-sm" aria-label="Bộ lọc công việc">
            <div className="grid gap-3 md:grid-cols-[minmax(200px,1fr)_220px_190px_auto]">
              <label className="relative">
                <span className="sr-only">Tìm theo tiêu đề</span>
                <span className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-slate-400">⌕</span>
                <input className="w-full rounded-xl border border-slate-200 bg-slate-50 py-2.5 pl-9 pr-3 text-sm outline-none focus:border-indigo-400 focus:ring-4 focus:ring-indigo-50" placeholder="Tìm công việc…" value={filterText} onChange={(event) => setFilterText(event.target.value)} />
              </label>
              <label>
                <span className="sr-only">Lọc theo người phụ trách</span>
                <select className="w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm" value={assigneeFilter} onChange={(event) => setAssigneeFilter(event.target.value)}>
                  <option value="">Mọi người phụ trách</option><option value="unassigned">Chưa giao</option>
                  {allAssignees.map((id) => <option key={id} value={id}>{id === user?.id ? 'Được giao cho tôi' : `Thành viên ${id.slice(0, 8)}`}</option>)}
                </select>
              </label>
              <label>
                <span className="sr-only">Lọc theo độ ưu tiên</span>
                <select className="w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm" value={priorityFilter} onChange={(event) => setPriorityFilter(event.target.value)}>
                  <option value="">Mọi độ ưu tiên</option>{PRIORITIES.map((priority) => <option key={priority} value={priority}>{PRIORITY_LABEL[priority]}</option>)}
                </select>
              </label>
              <button className="rounded-xl px-4 py-2 text-sm font-semibold text-slate-600 transition hover:bg-slate-100 disabled:opacity-40" onClick={clearFilters} disabled={!filtersActive}>Xóa lọc</button>
            </div>
            {filtersActive && <p className="mt-2 px-1 text-xs text-slate-500">Bộ lọc kết hợp với nhau. Kéo thả thẻ sẽ bật lại khi xóa bộ lọc.</p>}
            {!allTasksComplete && <p className="mt-2 px-1 text-xs text-amber-700">Kết quả bộ lọc chỉ gồm công việc đã tải; tải đủ từng cột để có kết quả toàn board.</p>}
          </section>

          {notice && <div className={`mb-4 flex items-center justify-between gap-4 rounded-xl px-4 py-3 text-sm ${syncBlocked ? 'bg-amber-50 text-amber-800' : 'bg-indigo-50 text-indigo-800'}`}>
            <span role="status" aria-live="polite">{notice}</span>
            {syncBlocked && connectionStatus === 'connected' && <button className="font-semibold underline" onClick={() => scheduleBoardSync({ ...emptyBoardSyncScopes(), full: true })}>Thử đồng bộ</button>}
            {(connectionStatus === 'error' || connectionStatus === 'offline') && <button className="font-semibold underline" onClick={realtime.retry}>Kết nối lại</button>}
          </div>}
          <div className={`mb-4 flex items-center justify-between gap-3 rounded-xl border px-4 py-2.5 text-sm ${connectionStatus === 'connected' && !syncBlocked ? 'border-emerald-200 bg-emerald-50 text-emerald-800' : 'border-amber-200 bg-amber-50 text-amber-900'}`} role="status" aria-live="polite">
            <span>{connectionStatus === 'connected' && !syncBlocked ? 'Đồng bộ realtime đang hoạt động' : connectionStatus === 'offline' ? 'Mất mạng · board chỉ đọc' : connectionStatus === 'error' ? 'Realtime chưa kết nối' : connectionStatus === 'reconnecting' ? 'Đang kết nối lại · dữ liệu cần đối soát' : connectionStatus === 'connecting' ? 'Đang kết nối đồng bộ board…' : 'Chưa kết nối đồng bộ board'}</span>
            {connectionStatus === 'connected' && !syncBlocked && <span className="text-xs">Dữ liệu đã đối soát</span>}
          </div>
          {board.columns.length === 0 && <div className="rounded-2xl border border-dashed border-slate-300 bg-white p-12 text-center text-sm text-slate-500">Board này chưa có cột nào.</div>}

          <DragDropProvider<DragData> onDragStart={handleDragStart} onDragOver={onDragOver} onDragEnd={onDragEnd}>
            <div className="flex min-h-[65vh] items-start gap-4 overflow-x-auto pb-8" aria-label="Kanban board">
              {activeColumns.map(({ visibleTasks, ...column }, columnIndex) => <BoardColumnView
                key={column.id}
                column={column}
                visibleTasks={visibleTasks}
                availableColumns={board.columns}
                index={columnIndex}
                activeFilters={filtersActive}
                canReorder={!board.archived && !pending && !syncBlocked && connectionStatus === 'connected'}
                canMoveCards={!board.archived && !pending && !syncBlocked && connectionStatus === 'connected' && !filtersActive && column.complete}
                onMoveTask={moveTaskFromMenu}
                onOpenTask={(taskId) => { setSelectedTaskDeleted(false); setSelectedTaskId(taskId); }}
                onRetry={() => void loadColumn(column.id, generationRef.current)}
                onLoadMore={() => void loadColumn(column.id, generationRef.current, false)}
              />)}
            </div>
          </DragDropProvider>
          <p className="sr-only" aria-live="assertive">{activeDragName ? `Đang di chuyển ${activeDragName}` : notice ?? ''}</p>
          {selectedTaskId && <Suspense fallback={<div className="fixed inset-0 z-50 grid place-items-center bg-slate-950/50 p-4" role="status">Đang mở chi tiết task…</div>}><TaskDetailModal key={`${board.id}:${selectedTaskId}`} taskId={selectedTaskId}
            canEdit={!board.archived && !pending && !syncBlocked && connectionStatus === 'connected'}
            detailRefreshVersion={taskDetailRefreshVersion}
            commentsRefreshVersion={commentsRefreshVersion}
            remoteDeleted={selectedTaskDeleted}
            onClose={() => setSelectedTaskId(null)} onSaved={handleTaskSaved} /></Suspense>}
        </>}
      </div>
    </main>
  );
}

function BoardColumnView({
  column, visibleTasks, availableColumns, index, activeFilters, canReorder, canMoveCards, onMoveTask, onRetry, onLoadMore,
  onOpenTask,
}: {
  column: BoardColumn;
  visibleTasks: TaskListItem[];
  availableColumns: BoardColumn[];
  index: number;
  activeFilters: boolean;
  canReorder: boolean;
  canMoveCards: boolean;
  onMoveTask(taskId: string, destinationColumnId: string): void;
  onOpenTask(taskId: string): void;
  onRetry(): void;
  onLoadMore(): void;
}) {
  const accepts = column.complete ? ['column', 'task'] : ['column'];
  const { ref, handleRef, isDragging, isDropTarget } = useSortable({
    id: `column:${column.id}`,
    index,
    type: 'column',
    accept: accepts,
    group: 'board-columns',
    disabled: !canReorder,
    data: { kind: 'column', columnId: column.id },
  });

  return (
    <section
      ref={ref}
      className={`w-[min(86vw,340px)] shrink-0 rounded-2xl border bg-slate-100/80 transition ${isDragging ? 'opacity-50' : ''} ${isDropTarget ? 'border-indigo-400 ring-2 ring-indigo-100' : 'border-slate-200'}`}
      aria-label={`Cột ${column.name}`}
    >
      <header className="flex items-center gap-2 border-b border-slate-200/80 px-4 py-3.5">
        <button ref={handleRef} type="button" disabled={!canReorder} className="grid size-8 shrink-0 place-items-center rounded-lg text-slate-400 hover:bg-white hover:text-indigo-600 focus:outline-none focus:ring-2 focus:ring-indigo-500 disabled:cursor-default" aria-label={`Sắp xếp cột ${column.name}`} title="Kéo để sắp xếp cột">⠿</button>
        <div className="min-w-0 flex-1">
          <h2 className="truncate text-sm font-bold text-slate-800">{column.name}</h2>
          <p className="text-xs text-slate-400">{column.complete ? `${column.totalElements} công việc` : `${column.tasks.length} đã tải`}</p>
        </div>
        <span className="rounded-full bg-white px-2.5 py-1 text-xs font-semibold text-slate-500">{column.tasks.length}</span>
      </header>
      <div className="max-h-[calc(100vh-250px)] min-h-32 space-y-3 overflow-y-auto p-3" aria-label={`Danh sách công việc ${column.name}`}>
        {column.loading && column.tasks.length === 0 && <div className="space-y-3" aria-label="Đang tải công việc"><div className="h-24 animate-pulse rounded-xl bg-white" /><div className="h-24 animate-pulse rounded-xl bg-white" /></div>}
        {column.error && <div className="rounded-xl border border-rose-200 bg-rose-50 p-3 text-xs text-rose-700" role="alert"><p>{column.error}</p><button className="mt-2 font-semibold underline" onClick={onRetry}>Thử tải lại</button></div>}
        {!column.loading && !column.error && visibleTasks.length === 0 && <EmptyTaskTarget column={column} canMoveCards={canMoveCards} activeFilters={activeFilters} />}
        {visibleTasks.map((task, taskIndex) => <TaskCard key={task.id} task={task} column={column} columns={availableColumns} index={taskIndex} disabled={!canMoveCards} onMoveTask={onMoveTask} onOpenTask={onOpenTask} />)}
        {column.complete && column.totalElements > column.tasks.length && <button className="w-full rounded-xl border border-dashed border-slate-300 bg-white/60 px-3 py-3 text-xs font-semibold text-slate-600 hover:bg-white" onClick={onLoadMore}>Tải thêm công việc</button>}
        {!column.complete && !column.loading && !column.error && column.totalElements > column.tasks.length && <button className="w-full rounded-xl border border-dashed border-amber-300 bg-amber-50 px-3 py-3 text-xs font-semibold text-amber-800" onClick={onLoadMore}>Tải tiếp {Math.max(0, column.totalElements - column.tasks.length)} công việc</button>}
      </div>
    </section>
  );
}

function EmptyTaskTarget({ column, canMoveCards, activeFilters }: { column: BoardColumn; canMoveCards: boolean; activeFilters: boolean }) {
  const { ref, isDropTarget } = useDroppable({
    id: `empty:${column.id}`,
    type: 'task',
    accept: 'task',
    disabled: !canMoveCards,
    data: { kind: 'column', columnId: column.id },
  });
  return <div ref={ref} className={`grid min-h-24 place-items-center rounded-xl border border-dashed px-4 text-center text-xs ${isDropTarget ? 'border-indigo-400 bg-indigo-50 text-indigo-700' : 'border-slate-300 bg-white/60 text-slate-400'}`}>
    {activeFilters ? 'Không có công việc phù hợp' : canMoveCards ? `Thả công việc vào ${column.name}` : 'Chưa có công việc'}
  </div>;
}

function TaskCard({ task, column, columns, index, disabled, onMoveTask, onOpenTask }: { task: TaskListItem; column: BoardColumn; columns: BoardColumn[]; index: number; disabled: boolean; onMoveTask(taskId: string, destinationColumnId: string): void; onOpenTask(taskId: string): void }) {
  const [showMoveMenu, setShowMoveMenu] = useState(false);
  const { ref, handleRef, isDragging, isDropTarget } = useSortable({
    id: `task:${task.id}`,
    index,
    type: 'task',
    accept: 'task',
    group: 'board-tasks',
    disabled,
    data: { kind: 'task', taskId: task.id, columnId: column.id },
  });
  const dueDate = task.dueDate ? new Date(task.dueDate) : null;
  const overdue = dueDate && dueDate.getTime() < Date.now();
  const priorityClass: Record<TaskPriority, string> = {
    LOW: 'bg-slate-100 text-slate-600',
    MEDIUM: 'bg-sky-100 text-sky-700',
    HIGH: 'bg-amber-100 text-amber-800',
    URGENT: 'bg-rose-100 text-rose-700',
  };
  return (
      <article id={`task-drop-${task.id}`} ref={ref} className={`rounded-xl border border-slate-200 bg-white p-4 shadow-sm transition hover:-translate-y-0.5 hover:shadow-md ${isDragging ? 'opacity-35' : ''} ${isDropTarget ? 'border-indigo-400 ring-2 ring-indigo-100' : ''}`}>
      <div className="mb-3 flex items-start gap-2">
        <h3 className="min-w-0 flex-1 break-words text-sm font-semibold leading-5 text-slate-800"><button type="button" onClick={() => onOpenTask(task.id)} className="text-left hover:text-indigo-700 focus:outline-none focus-visible:rounded focus-visible:ring-2 focus-visible:ring-indigo-500" aria-label={`Mở chi tiết ${task.title}`}>{task.title}</button></h3>
        <button ref={handleRef} type="button" disabled={disabled} className="grid size-7 shrink-0 place-items-center rounded-md text-slate-400 hover:bg-slate-100 hover:text-indigo-600 focus:outline-none focus:ring-2 focus:ring-indigo-500 disabled:cursor-default" aria-label={`Kéo để di chuyển ${task.title}`} title={disabled ? 'Xóa bộ lọc và tải đủ cột để sắp xếp' : 'Kéo để di chuyển'}>⠿</button>
      </div>
      <div className="flex flex-wrap items-center gap-2">
        <span className={`rounded-md px-2 py-1 text-[10px] font-bold uppercase tracking-wide ${priorityClass[task.priority]}`}>{PRIORITY_LABEL[task.priority]}</span>
        {task.assigneeId && <span className="max-w-32 truncate rounded-full bg-violet-50 px-2 py-1 text-[10px] font-medium text-violet-700" title={task.assigneeId}>#{task.assigneeId.slice(0, 8)}</span>}
        {dueDate && <span className={`ml-auto text-[10px] font-medium ${overdue ? 'text-rose-600' : 'text-slate-400'}`}>{new Intl.DateTimeFormat('vi-VN', { day: '2-digit', month: 'short' }).format(dueDate)}</span>}
      </div>
      <div className="mt-3 border-t border-slate-100 pt-2">
        {showMoveMenu ? <label className="flex items-center gap-2 text-xs text-slate-500">
          <span className="shrink-0">Di chuyển đến</span>
          <select autoFocus className="min-w-0 flex-1 rounded-lg border border-slate-200 bg-white px-2 py-1.5 text-xs text-slate-700" value="" onChange={(event) => {
            if (event.target.value) onMoveTask(task.id, event.target.value);
            setShowMoveMenu(false);
          }} onBlur={() => setShowMoveMenu(false)}>
            <option value="" disabled>Chọn cột…</option>
            {columns.filter((item) => item.id !== column.id && item.complete).map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}
          </select>
        </label> : <button type="button" className="text-xs font-medium text-slate-400 transition hover:text-indigo-600 focus:outline-none focus:underline disabled:cursor-default disabled:opacity-50" disabled={disabled || columns.filter((item) => item.id !== column.id && item.complete).length === 0} onClick={() => setShowMoveMenu(true)}>Di chuyển…</button>}
      </div>
    </article>
  );
}
