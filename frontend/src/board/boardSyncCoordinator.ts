import { getBoard, listTasks } from '../api/boardApi';
import type { BoardResponse, TaskListItem, TaskPageResponse } from '../types/board';

export const MAX_PAGES_PER_COLUMN = 50;
const BOARD_SYNC_FETCH_CONCURRENCY = 4;

export interface BoardSyncScopes {
  full: boolean;
  refreshColumns: Set<string>;
  refreshTaskDetail: boolean;
  refreshComments: boolean;
}

export interface TaskColumnSnapshot {
  tasks: TaskListItem[];
  page: TaskPageResponse;
  complete: boolean;
  nextPage: number;
}

export function emptyBoardSyncScopes(): BoardSyncScopes {
  return { full: false, refreshColumns: new Set(), refreshTaskDetail: false, refreshComments: false };
}

export function mergeBoardSyncScopes(target: BoardSyncScopes, incoming: BoardSyncScopes) {
  target.full ||= incoming.full;
  target.refreshTaskDetail ||= incoming.refreshTaskDetail;
  target.refreshComments ||= incoming.refreshComments;
  incoming.refreshColumns.forEach((id) => target.refreshColumns.add(id));
}

export function hasBoardSyncWork(scopes: BoardSyncScopes) {
  return scopes.full || scopes.refreshColumns.size > 0 || scopes.refreshTaskDetail || scopes.refreshComments;
}

export async function mapWithConcurrency<T>(items: T[], limit: number, worker: (item: T) => Promise<void>) {
  let next = 0;
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, async () => {
    while (next < items.length) {
      const item = items[next++];
      if (item !== undefined) await worker(item);
    }
  }));
}

export async function fetchEveryTaskPage(
  request: <T>(path: string, init?: RequestInit) => Promise<T>,
  columnId: string,
  startPage = 0,
  signal?: AbortSignal,
  maxPages = MAX_PAGES_PER_COLUMN,
): Promise<TaskColumnSnapshot> {
  let page = await listTasks(request, columnId, startPage, signal);
  const tasks = [...page.items];
  let currentPage = startPage + 1;
  const pageLimit = startPage + Math.max(1, maxPages);
  while (currentPage < page.totalPages && currentPage < pageLimit) {
    page = await listTasks(request, columnId, currentPage, signal);
    tasks.push(...page.items);
    currentPage += 1;
    if (page.items.length === 0) break;
  }
  const uniqueTasks = [...new Map(tasks.map((task) => [task.id, task])).values()]
    .sort((left, right) => left.position - right.position || left.id.localeCompare(right.id));
  return { tasks: uniqueTasks, page, complete: currentPage >= page.totalPages || page.totalPages === 0, nextPage: currentPage };
}

export async function fetchBoardSnapshot(
  request: <T>(path: string, init?: RequestInit) => Promise<T>,
  boardId: string,
  scopes: BoardSyncScopes,
  signal: AbortSignal,
  loadedPagesByColumn: ReadonlyMap<string, number> = new Map(),
): Promise<{ board: BoardResponse; snapshots: Map<string, TaskColumnSnapshot>; requestedColumnIds: Set<string> }> {
  const board = await getBoard(request, boardId, signal);
  const columns = [...board.columns].sort((left, right) => left.position - right.position);
  const requestedColumnIds = new Set(scopes.full ? columns.map((column) => column.id) : scopes.refreshColumns);
  if ([...requestedColumnIds].some((columnId) => !columns.some((column) => column.id === columnId))) {
    columns.forEach((column) => requestedColumnIds.add(column.id));
  }

  const snapshots = new Map<string, TaskColumnSnapshot>();
  await mapWithConcurrency([...requestedColumnIds], BOARD_SYNC_FETCH_CONCURRENCY, async (columnId) => {
    if (!columns.some((column) => column.id === columnId)) return;
    // Columns not loaded yet (initial load, new columns) fetch up to the page limit, like loadColumn.
    const maxPages = loadedPagesByColumn.get(columnId) ?? MAX_PAGES_PER_COLUMN;
    const snapshot = await fetchEveryTaskPage(request, columnId, 0, signal, maxPages);
    snapshots.set(columnId, snapshot);
  });
  return { board, snapshots, requestedColumnIds };
}
