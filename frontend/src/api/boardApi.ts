import type {
  BoardPageResponse,
  BoardResponse,
  ColumnResponse,
  TaskPageResponse,
  WorkspaceSummary,
} from '../types/board';

export type ApiRequest = <T>(path: string, init?: RequestInit) => Promise<T>;

export function listWorkspaces(request: ApiRequest) {
  return request<WorkspaceSummary[]>('/api/v1/auth/workspaces');
}

export function listBoards(request: ApiRequest, workspaceId: string, page = 0) {
  return request<BoardPageResponse>(`/api/v1/workspaces/${workspaceId}/boards?page=${page}&size=100&includeArchived=true`);
}

export function getBoard(request: ApiRequest, boardId: string) {
  return request<BoardResponse>(`/api/v1/boards/${boardId}`);
}

export function listColumns(request: ApiRequest, boardId: string) {
  return request<ColumnResponse[]>(`/api/v1/boards/${boardId}/columns`);
}

export function listTasks(request: ApiRequest, columnId: string, page: number) {
  return request<TaskPageResponse>(`/api/v1/columns/${columnId}/tasks?page=${page}&size=100`);
}

export function moveTask(request: ApiRequest, taskId: string, columnId: string, position: number, token: string) {
  const headers: Record<string, string> = {};
  if (token) headers['X-Turnstile-Token'] = token;
  return request(`/api/v1/tasks/${taskId}/move`, {
    method: 'PATCH',
    headers,
    body: JSON.stringify({ columnId, position }),
  });
}

export function reorderColumn(request: ApiRequest, columnId: string, position: number) {
  return request<ColumnResponse[]>(`/api/v1/columns/${columnId}/reorder`, {
    method: 'PATCH',
    body: JSON.stringify({ position }),
  });
}
