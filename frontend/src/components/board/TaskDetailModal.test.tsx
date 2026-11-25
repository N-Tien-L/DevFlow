// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as authModule from '../../auth/AuthProvider';
import { ApiError, type TaskDetail } from '../../types/board';
import { TaskDetailModal } from './TaskDetailModal';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const baseTask: TaskDetail = {
  id: 'task-1',
  taskKey: 'T-048',
  boardId: 'board-1',
  workspaceId: 'ws-1',
  columnId: 'col-1',
  title: 'Cải thiện giao diện DevFlow',
  description: 'Chi tiết công việc',
  priority: 'HIGH',
  assigneeId: 'user-2',
  dueDate: null,
  position: 0,
  statusCategory: 'IN_PROGRESS',
  createdAt: '2026-11-20T08:00:00Z',
  updatedAt: '2026-11-25T08:00:00Z',
  version: 2,
};

function setNativeValue(element: HTMLInputElement | HTMLTextAreaElement, value: string) {
  const proto = element instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
  Object.getOwnPropertyDescriptor(proto, 'value')?.set?.call(element, value);
  element.dispatchEvent(new Event('input', { bubbles: true }));
}

const settle = () => act(() => new Promise<void>((resolve) => setTimeout(resolve, 10)));

describe('TaskDetailModal', () => {
  let root: Root | null = null;
  let container: HTMLDivElement | null = null;

  beforeEach(() => {
    container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
    if (!HTMLDialogElement.prototype.showModal) {
      HTMLDialogElement.prototype.showModal = function showModal() {
        this.setAttribute('open', '');
      };
    }
    if (!HTMLDialogElement.prototype.close) {
      HTMLDialogElement.prototype.close = function close() {
        this.removeAttribute('open');
      };
    }
  });

  afterEach(() => {
    if (root) {
      act(() => root?.unmount());
      root = null;
    }
    container?.remove();
    container = null;
    vi.restoreAllMocks();
  });

  function mockAuth(handler: (path: string, init?: RequestInit) => Promise<unknown>) {
    vi.spyOn(authModule, 'useAuth').mockReturnValue({
      user: { id: 'user-1', email: 'xuan@devflow.vn', fullName: 'Lê Viết Xuân', avatarUrl: null },
      ready: true,
      sessionGeneration: 1,
      sessionEnd: null,
      signIn: vi.fn(),
      signOut: vi.fn(),
      getValidAccessToken: vi.fn(),
      request: vi.fn(handler) as unknown as ReturnType<typeof authModule.useAuth>['request'],
    });
  }

  it('shows read-only state and resolves comment author names when canEdit is false', async () => {
    mockAuth(async (path) => {
      if (path === '/api/v1/tasks/task-1') return baseTask;
      if (path.includes('/comments')) {
        return {
          items: [{ id: 'c-1', taskId: 'task-1', authorId: 'user-2', content: 'Đã review giao diện', createdAt: '2026-11-25T08:10:00Z', updatedAt: '2026-11-25T08:10:00Z' }],
          page: 0, size: 20, totalElements: 1, totalPages: 1,
        };
      }
      if (path.includes('/members')) {
        return {
          items: [{ userId: 'user-2', fullName: 'Tiến Lâm', avatarUrl: null }],
          page: 0, size: 20, totalElements: 1, totalPages: 1,
        };
      }
      throw new Error(`Unexpected path ${path}`);
    });

    await act(async () => {
      root?.render(<TaskDetailModal taskId="task-1" canEdit={false} onClose={vi.fn()} onSaved={vi.fn()} />);
    });
    await settle();

    expect(container?.textContent).toContain('Chỉ xem');
    expect(container?.textContent).toContain('Tiến Lâm');
    expect(container?.querySelector<HTMLButtonElement>('button[type="submit"]')?.disabled).toBe(true);
  });

  it('guards unsaved changes before closing and surfaces 412 save conflict merge', async () => {
    let detailFetchCount = 0;
    const onClose = vi.fn();

    mockAuth(async (path, init) => {
      if (path === '/api/v1/tasks/task-1' && (!init?.method || init.method === 'GET')) {
        detailFetchCount += 1;
        return detailFetchCount === 1
          ? baseTask
          : { ...baseTask, version: 3, priority: 'URGENT', description: 'Bản cập nhật từ đồng đội' };
      }
      if (path === '/api/v1/tasks/task-1' && init?.method === 'PUT') {
        throw new ApiError('Conflict', 412);
      }
      if (path.includes('/comments')) {
        return { items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 };
      }
      if (path.includes('/members')) {
        return { items: [{ userId: 'user-2', fullName: 'Tiến Lâm', avatarUrl: null }], page: 0, size: 20, totalElements: 1, totalPages: 1 };
      }
      throw new Error(`Unexpected ${path}`);
    });

    await act(async () => {
      root?.render(<TaskDetailModal taskId="task-1" canEdit onClose={onClose} onSaved={vi.fn()} />);
    });
    await settle();

    const titleInput = container?.querySelector('input[required]') as HTMLInputElement;
    await act(async () => {
      setNativeValue(titleInput, 'Tiêu đề bản nháp mới');
    });

    expect(container?.textContent).toContain('Có thay đổi chưa lưu');

    // Submit triggers 412 conflict and shows merge CTA
    await act(async () => {
      container?.querySelector<HTMLButtonElement>('button[type="submit"]')?.click();
    });
    await settle();

    expect(container?.querySelector('[role="alert"]')?.textContent).toContain('Nạp bản mới và giữ phần đang sửa');

    // Trying to close with unsaved draft opens discard alertdialog instead of closing directly
    await act(async () => {
      container?.querySelector<HTMLButtonElement>('button[aria-label="Đóng cửa sổ chi tiết"]')?.click();
    });

    expect(onClose).not.toHaveBeenCalled();
    const discardDialog = container?.querySelector('[role="alertdialog"]');
    expect(discardDialog).not.toBeNull();

    const discardBtn = Array.from(discardDialog?.querySelectorAll('button') ?? []).find((btn) => btn.textContent?.includes('Bỏ thay đổi'));
    await act(async () => {
      discardBtn?.click();
    });
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
