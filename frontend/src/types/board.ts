export type TaskPriority = 'LOW' | 'MEDIUM' | 'HIGH' | 'URGENT';

export type ColumnStatusCategory = 'TODO' | 'IN_PROGRESS' | 'IN_REVIEW' | 'DONE';

export interface UserSummary {
  id: string;
  email: string;
  fullName: string;
  avatarUrl: string | null;
}

export interface WorkspaceSummary {
  id: string;
  name: string;
  slug: string;
  role: string;
  createdAt: string;
}

export interface AuthResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresIn: number;
  user: UserSummary;
}

export interface ColumnResponse {
  id: string;
  boardId: string;
  name: string;
  position: number;
  statusCategory: ColumnStatusCategory;
  createdAt: string;
  updatedAt: string;
}

export interface TaskListItem {
  id: string;
  columnId: string;
  title: string;
  priority: TaskPriority;
  assigneeId: string | null;
  dueDate: string | null;
  position: number;
  statusCategory: ColumnStatusCategory;
  updatedAt: string;
}

export interface TaskPageResponse {
  items: TaskListItem[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface BoardResponse {
  id: string;
  workspaceId: string;
  name: string;
  description: string | null;
  archived: boolean;
  createdAt: string;
  updatedAt: string;
  columns: ColumnResponse[];
}

export interface BoardPageResponse {
  items: BoardSummary[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export type BoardSummary = Omit<BoardResponse, 'columns'>;

export interface BoardColumn extends ColumnResponse {
  tasks: TaskListItem[];
  totalElements: number;
  totalPages: number;
  loadedPages: number;
  complete: boolean;
  loading: boolean;
  error: string | null;
}

export interface BoardState extends Omit<BoardResponse, 'columns'> {
  columns: BoardColumn[];
}

export interface ProblemDetail {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  code?: string;
  correlationId?: string;
  retryAfterSeconds?: number;
}

export class ApiError extends Error {
  readonly status: number;
  readonly problem?: ProblemDetail;
  readonly uncertain: boolean;

  constructor(
    message: string,
    status: number,
    problem?: ProblemDetail,
    uncertain = false,
  ) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.problem = problem;
    this.uncertain = uncertain;
  }
}

export function isTaskPriority(value: string): value is TaskPriority {
  return ['LOW', 'MEDIUM', 'HIGH', 'URGENT'].includes(value);
}

export function isColumnStatusCategory(value: string): value is ColumnStatusCategory {
  return ['TODO', 'IN_PROGRESS', 'IN_REVIEW', 'DONE'].includes(value);
}
