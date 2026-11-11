// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { TaskProposalCard } from './TaskProposalCard';
import type { ColumnResponse } from '../../types/board';
import type { ProposedTask } from '../../types/chat';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

describe('TaskProposalCard', () => {
  let container: HTMLDivElement;
  let root: Root;

  const mockColumns: ColumnResponse[] = [
    { id: 'col-1', boardId: 'board-1', name: 'To Do', position: 0, statusCategory: 'TODO', createdAt: '2026-10-24T00:00:00Z', updatedAt: '2026-10-24T00:00:00Z' },
    { id: 'col-2', boardId: 'board-1', name: 'In Progress', position: 1, statusCategory: 'IN_PROGRESS', createdAt: '2026-10-24T00:00:00Z', updatedAt: '2026-10-24T00:00:00Z' },
  ];

  const mockProposals: ProposedTask[] = [
    {
      title: 'Tạo endpoint xác thực',
      description: 'Cấu hình JWT filter và SecurityFilterChain',
      checklist: ['Viết controller', 'Thêm unit test'],
    },
    {
      title: 'Thiết kế form đăng ký',
      description: 'Tích hợp Cloudflare Turnstile bot protection',
      checklist: ['Turnstile widget', 'Xử lý token verification'],
    },
  ];

  beforeEach(() => {
    container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
  });

  afterEach(async () => {
    await act(async () => {
      root.unmount();
    });
    container.remove();
  });

  it('renders proposals with editable titles and columns dropdown', async () => {
    const mockRequest = vi.fn();

    await act(async () => {
      root.render(
        <TaskProposalCard
          proposals={mockProposals}
          columns={mockColumns}
          request={mockRequest}
        />
      );
    });

    const inputs = container.querySelectorAll('input[type="text"]');
    expect(inputs).toHaveLength(2);
    expect((inputs[0] as HTMLInputElement).value).toBe('Tạo endpoint xác thực');
    expect((inputs[1] as HTMLInputElement).value).toBe('Thiết kế form đăng ký');

    const select = container.querySelector('select');
    expect(select).not.toBeNull();
    expect(select?.querySelectorAll('option')).toHaveLength(2);
  });

  it('clicking "Tạo task" sends creation request and displays "Đã tạo"', async () => {
    const mockRequest = vi.fn().mockResolvedValue({
      id: 'task-101',
      title: 'Tạo endpoint xác thực',
      columnId: 'col-1',
    });

    await act(async () => {
      root.render(
        <TaskProposalCard
          proposals={mockProposals}
          columns={mockColumns}
          request={mockRequest}
        />
      );
    });

    const buttons = container.querySelectorAll('button');
    const createBtn = Array.from(buttons).find((b) => b.textContent?.includes('Tạo task'));
    expect(createBtn).toBeDefined();

    await act(async () => {
      createBtn?.click();
    });

    expect(mockRequest).toHaveBeenCalledWith(
      '/api/v1/columns/col-1/tasks',
      expect.objectContaining({
        method: 'POST',
      })
    );

    const updatedButtons = container.querySelectorAll('button');
    const createdBadge = Array.from(updatedButtons).find((b) => b.textContent?.includes('Đã tạo'));
    expect(createdBadge).toBeDefined();
    expect(createdBadge?.hasAttribute('disabled')).toBe(true);
  });
});
