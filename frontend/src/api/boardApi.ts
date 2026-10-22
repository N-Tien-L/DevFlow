import type {
  BoardPageResponse,
  BoardResponse,
  ColumnResponse,
  TaskPageResponse,
  TaskDetail,
  TaskCommentPage,
  TaskComment,
  WorkspaceMemberPage,
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

export function moveTask(request: ApiRequest, taskId: string, columnId: string, position: number) {
  return request(`/api/v1/tasks/${taskId}/move`, {
    method: 'PATCH',
    body: JSON.stringify({ columnId, position }),
  });
}

export function reorderColumn(request: ApiRequest, columnId: string, position: number) {
  return request<ColumnResponse[]>(`/api/v1/columns/${columnId}/reorder`, {
    method: 'PATCH',
    body: JSON.stringify({ position }),
  });
}

export function getTaskDetail(request: ApiRequest, taskId: string, signal?: AbortSignal) {
  return request<TaskDetail>(`/api/v1/tasks/${taskId}`, { signal });
}

export function updateTaskDetail(request: ApiRequest, task: TaskDetail, input: {
  title: string;
  description: string | null;
  priority: TaskDetail['priority'];
  assigneeId: string | null;
  dueDate: string | null;
}) {
  return request<TaskDetail>(`/api/v1/tasks/${task.id}`, {
    method: 'PUT',
    headers: {
      'If-Match': `"task-${task.id}-v${task.version}"`,
    },
    body: JSON.stringify(input),
  });
}

export function listWorkspaceMembers(request: ApiRequest, workspaceId: string, query = '', page = 0) {
  const params = new URLSearchParams({ q: query, page: String(page), size: '20' });
  return request<WorkspaceMemberPage>(`/api/v1/auth/workspaces/${workspaceId}/members?${params}`);
}

export function listTaskComments(request: ApiRequest, taskId: string, page = 0) {
  return request<TaskCommentPage>(`/api/v1/tasks/${taskId}/comments?page=${page}&size=20`);
}

export function createTaskComment(request: ApiRequest, taskId: string, content: string) {
  return request<TaskComment>(`/api/v1/tasks/${taskId}/comments`, {
    method: 'POST',
    body: JSON.stringify({ content }),
  });
}
