import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { DragDropProvider } from '@dnd-kit/react';
import type { DragEndEvent, DragOverEvent, DragStartEvent } from '@dnd-kit/react';
import { useSortable } from '@dnd-kit/react/sortable';
import { useDroppable } from '@dnd-kit/react';
import { applyColumnOrder, cloneColumns, locateTask, moveTaskBefore, reorderColumnById } from '../board/mutations';
import { getBoard, listBoards, listColumns, listTasks, listWorkspaces, moveTask, reorderColumn } from '../api/boardApi';
import { useAuth } from '../auth/AuthProvider';
import { useTurnstileToken } from '../hooks/useTurnstileToken';
import { ApiError, type BoardColumn, type BoardPageResponse, type BoardResponse, type BoardState, type BoardSummary, type ColumnResponse, type TaskListItem, type TaskPageResponse, type TaskPriority, type WorkspaceSummary } from '../types/board';

const MAX_PAGES_PER_COLUMN = 50;
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

async function fetchEveryTaskPage(
  request: <T>(path: string, init?: RequestInit) => Promise<T>,
  columnId: string,
  startPage = 0,
): Promise<{ tasks: TaskListItem[]; page: TaskPageResponse; complete: boolean; nextPage: number }> {
  let page = await listTasks(request, columnId, startPage);
  const tasks = [...page.items];
  let currentPage = startPage + 1;
  while (currentPage < page.totalPages && currentPage < MAX_PAGES_PER_COLUMN) {
    page = await listTasks(request, columnId, currentPage);
    tasks.push(...page.items);
    currentPage += 1;
    if (page.items.length === 0) break;
  }
  const uniqueTasks = [...new Map(tasks.map((task) => [task.id, task])).values()]
    .sort((left, right) => left.position - right.position || left.id.localeCompare(right.id));
  return { tasks: uniqueTasks, page, complete: currentPage >= page.totalPages || page.totalPages === 0, nextPage: currentPage };
}

async function mapWithConcurrency<T>(items: T[], limit: number, worker: (item: T) => Promise<void>) {
  let next = 0;
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, async () => {
    while (next < items.length) {
      const item = items[next++];
      if (item !== undefined) await worker(item);
    }
  }));
}

export default function BoardPage() {
  const { boardId } = useParams();
  const navigate = useNavigate();
  const { user, request, signOut } = useAuth();
  const turnstile = useTurnstileToken();
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
  const generationRef = useRef(0);

  const displayColumns = useMemo(() => previewColumns ?? board?.columns ?? [], [board?.columns, previewColumns]);
  const filtersActive = Boolean(filterText.trim() || assigneeFilter || priorityFilter);
  const allTasksComplete = Boolean(board?.columns.every((column) => column.complete));
  const allAssignees = useMemo(() => [...new Set((board?.columns ?? []).flatMap((column) => column.tasks.map((task) => task.assigneeId).filter((id): id is string => Boolean(id))))].sort(), [board]);
  boardRef.current = board;

  const updateColumn = useCallback((columnId: string, update: (column: BoardColumn) => BoardColumn, generation = generationRef.current) => {
    if (generation !== generationRef.current) return;
    setBoard((current) => current ? { ...current, columns: current.columns.map((column) => column.id === columnId ? update(column) : column) } : current);
  }, []);

  const loadColumn = useCallback(async (columnId: string, generation: number, replace = true): Promise<boolean> => {
    updateColumn(columnId, (column) => ({ ...column, loading: true, error: null }), generation);
    try {
      const pageStart = replace ? 0 : (boardRef.current?.columns.find((column) => column.id === columnId)?.loadedPages ?? 0);
      const result = await fetchEveryTaskPage(request, columnId, pageStart);
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
      if (!result.complete) setNotice(`Cột chưa tải hết do giới hạn ${MAX_PAGES_PER_COLUMN} trang. Dùng nút tải thêm để tiếp tục.`);
      return result.complete;
    } catch (reason) {
      updateColumn(columnId, (column) => ({ ...column, loading: false, error: errorMessage(reason) }), generation);
      return false;
    }
  }, [request, updateColumn]);

  const loadBoard = useCallback(async (id: string) => {
    const generation = ++generationRef.current;
    setLoading(true);
    setError(null);
    setNotice(null);
    setSyncBlocked(false);
    setBoard(null);
    try {
      const response = await getBoard(request, id);
      if (generation !== generationRef.current) return;
      const initial = toBoardState(response);
      setBoard(initial);
      setWorkspaceId(response.workspaceId);
      const columnIds = initial.columns.map((column) => column.id);
      await mapWithConcurrency(columnIds, 4, async (columnId) => {
        await loadColumn(columnId, generation);
      });
    } catch (reason) {
      if (generation === generationRef.current) setError(errorMessage(reason));
    } finally {
      if (generation === generationRef.current) setLoading(false);
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
      void loadBoard(boardId);
      return () => { generationRef.current += 1; };
    }
    setBoard(null);
    setLoading(false);
    return undefined;
  }, [boardId, loadBoard]);

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

  const refreshColumns = useCallback(async (columnIds: string[], generation: number) => {
    const outcomes = await Promise.all([...new Set(columnIds)].map((columnId) => loadColumn(columnId, generation)));
    if (outcomes.some((complete) => !complete)) throw new Error('Board reconciliation was incomplete.');
  }, [loadColumn]);

  const refreshBoardColumns = useCallback(async (generation: number) => {
    const currentBoardId = boardRef.current?.id;
    if (!currentBoardId) return;
    const columns = await listColumns(request, currentBoardId);
    if (generation !== generationRef.current) return;
    setBoard((current) => current ? {
      ...current,
      columns: columns.sort((left, right) => left.position - right.position).map((column) => {
        const existing = current.columns.find((item) => item.id === column.id);
        return existing ? { ...existing, ...column } : { ...column, tasks: [], totalElements: 0, totalPages: 1, loadedPages: 0, complete: false, loading: false, error: null };
      }),
    } : current);
  }, [request]);

  const announce = (message: string) => setNotice(message);

  const onDragOver = (event: DragOverEvent) => {
    if (pending || syncBlocked) return;
    const source = event.operation.source?.data as DragData | undefined;
    const target = event.operation.target?.data as DragData | undefined;
    if (source?.kind !== 'task' || !source.taskId || !target) return;
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
    const position = locateTask(draft, taskId);
    const previous = locateTask(original, taskId);
    if (!position || !previous || (position.columnId === previous.columnId && position.index === previous.index)) {
      previewRef.current = null;
      setPreviewColumns(null);
      return;
    }
    const fromColumn = original.find((column) => column.id === previous.columnId);
    const toColumn = draft.find((column) => column.id === position.columnId);
    if (!fromColumn?.complete || !toColumn?.complete) {
      previewRef.current = null;
      setPreviewColumns(null);
      announce('Cần tải đủ công việc trong hai cột trước khi sắp xếp.');
      return;
    }

    const generation = generationRef.current;
    const optimistic = draft.map((column) => ({ ...column, loading: false }));
    previewRef.current = null;
    setPreviewColumns(null);
    setBoard((current) => current ? { ...current, columns: optimistic } : current);
    setPending(true);
    setNotice('Đang xác minh và lưu vị trí…');
    let committed = false;
    try {
      const token = await turnstile.getToken();
      await moveTask(request, taskId, position.columnId, position.index, token);
      committed = true;
      setNotice('Đã lưu vị trí. Đang đồng bộ board…');
      await refreshColumns([previous.columnId, position.columnId], generation);
      if (generation === generationRef.current) {
        setSyncBlocked(false);
        setNotice('Đã lưu vị trí công việc.');
      }
    } catch (reason) {
      const uncertain = committed || (reason instanceof ApiError && reason.uncertain);
      if (generation === generationRef.current && !uncertain) {
        setBoard((current) => current ? { ...current, columns: original } : current);
        setNotice(errorMessage(reason));
      } else if (generation === generationRef.current) {
        setSyncBlocked(true);
        setNotice(committed ? 'Đã lưu nhưng chưa tải được dữ liệu mới. Đang kiểm tra lại…' : 'Chưa xác nhận được kết quả lưu. Đang kiểm tra lại dữ liệu máy chủ…');
      }
      try {
        await refreshColumns([previous.columnId, position.columnId], generation);
        if (generation === generationRef.current) {
          setSyncBlocked(false);
          if (uncertain) setNotice('Đã đối chiếu lại với máy chủ. Bạn có thể tiếp tục.');
        }
      } catch {
        if (generation === generationRef.current) {
          setSyncBlocked(true);
          setNotice(`${errorMessage(reason)} Dữ liệu chưa đối soát được; hãy tải lại board.`);
        }
      }
    } finally {
      if (generation === generationRef.current) setPending(false);
    }
  };

  const onDragEnd = (event: DragEndEvent) => {
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
    if (sourceData.kind === 'column' && targetData?.kind === 'column' && board) {
      const original = board?.columns ?? [];
      const draft = reorderColumnById(original, sourceData.columnId, targetData.columnId);
      setActiveDragName('');
      if (draft === original) return;
      const toPosition = draft.findIndex((column) => column.id === sourceData.columnId);
      const generation = generationRef.current;
      setBoard((current) => current ? { ...current, columns: draft } : current);
      setPending(true);
      setNotice('Đang lưu thứ tự cột…');
      void reorderColumn(request, sourceData.columnId, toPosition).then((response: ColumnResponse[]) => {
        if (generation !== generationRef.current) return;
        setBoard((current) => current ? { ...current, columns: applyColumnOrder(current.columns, response) } : current);
        setNotice('Đã lưu thứ tự cột.');
      }).catch(async (reason: unknown) => {
        if (generation !== generationRef.current) return;
        if (reason instanceof ApiError && !reason.uncertain) setBoard((current) => current ? { ...current, columns: original } : current);
        try {
          await refreshBoardColumns(generation);
          if (generation === generationRef.current) setNotice(errorMessage(reason));
        } catch {
          setSyncBlocked(true);
          setNotice('Chưa xác nhận được thứ tự cột. Hãy tải lại board.');
        }
      }).finally(() => { if (generation === generationRef.current) setPending(false); });
    }
  };

  const handleDragStart = (event: DragStartEvent) => {
    const source = event.operation.source?.data as DragData | undefined;
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

          {notice && <div className={`mb-4 flex items-center justify-between gap-4 rounded-xl px-4 py-3 text-sm ${syncBlocked ? 'bg-amber-50 text-amber-800' : 'bg-indigo-50 text-indigo-800'}`} role="status" aria-live="polite">
            <span>{notice}</span>
            {syncBlocked && <button className="font-semibold underline" onClick={() => boardId && void loadBoard(boardId)}>Tải lại board</button>}
          </div>}
          {!turnstile.enabled && !board.archived && <p className="mb-4 rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800">Thiếu cấu hình Turnstile. Board vẫn xem được, thao tác kéo thẻ sẽ bị khóa.</p>}
          {turnstile.error && <p className="mb-4 rounded-xl bg-rose-50 px-4 py-3 text-sm text-rose-700" role="alert">{turnstile.error}</p>}
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
                canReorder={!board.archived && !pending && !syncBlocked}
                canMoveCards={!board.archived && !pending && !syncBlocked && !filtersActive && turnstile.enabled && turnstile.ready && column.complete}
                onMoveTask={moveTaskFromMenu}
                onRetry={() => void loadColumn(column.id, generationRef.current)}
                onLoadMore={() => void loadColumn(column.id, generationRef.current, false)}
              />)}
            </div>
          </DragDropProvider>
          <div ref={turnstile.hostRef} className="fixed bottom-4 left-4 z-40" />
          <p className="sr-only" aria-live="assertive">{activeDragName ? `Đang di chuyển ${activeDragName}` : notice ?? ''}</p>
        </>}
      </div>
    </main>
  );
}

function BoardColumnView({
  column, visibleTasks, availableColumns, index, activeFilters, canReorder, canMoveCards, onMoveTask, onRetry, onLoadMore,
}: {
  column: BoardColumn;
  visibleTasks: TaskListItem[];
  availableColumns: BoardColumn[];
  index: number;
  activeFilters: boolean;
  canReorder: boolean;
  canMoveCards: boolean;
  onMoveTask(taskId: string, destinationColumnId: string): void;
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
        {visibleTasks.map((task, taskIndex) => <TaskCard key={task.id} task={task} column={column} columns={availableColumns} index={taskIndex} disabled={!canMoveCards} onMoveTask={onMoveTask} />)}
        {column.complete && column.totalElements > column.tasks.length && <button className="w-full rounded-xl border border-dashed border-slate-300 bg-white/60 px-3 py-3 text-xs font-semibold text-slate-600 hover:bg-white" onClick={onLoadMore}>Tải thêm công việc</button>}
        {!column.complete && !column.loading && !column.error && column.totalPages > MAX_PAGES_PER_COLUMN && <button className="w-full rounded-xl border border-dashed border-amber-300 bg-amber-50 px-3 py-3 text-xs font-semibold text-amber-800" onClick={onLoadMore}>Tải tiếp {Math.max(0, column.totalElements - column.tasks.length)} công việc</button>}
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

function TaskCard({ task, column, columns, index, disabled, onMoveTask }: { task: TaskListItem; column: BoardColumn; columns: BoardColumn[]; index: number; disabled: boolean; onMoveTask(taskId: string, destinationColumnId: string): void }) {
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
        <h3 className="min-w-0 flex-1 break-words text-sm font-semibold leading-5 text-slate-800">{task.title}</h3>
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
