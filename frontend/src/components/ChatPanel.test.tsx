// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ChatConnectionStatus, ChatFrame, ChatMessageRequest } from '../types/chat';
import ChatPanel from './ChatPanel';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const mocks = vi.hoisted(() => ({
  status: 'connected' as ChatConnectionStatus,
  onFrame: null as ((frame: ChatFrame) => void) | null,
  sendMessage: vi.fn((request: ChatMessageRequest) => Boolean(request)),
  cancelStream: vi.fn(),
  reconnect: vi.fn(),
  request: vi.fn(),
  getValidAccessToken: vi.fn(),
}));

vi.mock('../auth/AuthProvider', () => ({
  useAuth: () => ({
    user: { id: 'user-1', email: 'an@devflow.test', fullName: 'An' },
    request: mocks.request,
    getValidAccessToken: mocks.getValidAccessToken,
  }),
}));

vi.mock('../hooks/useChatWebSocket', () => ({
  useChatWebSocket: ({ onFrame }: { onFrame: (frame: ChatFrame) => void }) => {
    mocks.onFrame = onFrame;
    return {
      status: mocks.status,
      sendMessage: mocks.sendMessage,
      cancelStream: mocks.cancelStream,
      reconnect: mocks.reconnect,
    };
  },
}));

const BOARD = {
  id: 'board-1',
  workspaceId: 'ws-2',
  name: 'Sprint 3',
  description: null,
  archived: false,
  createdAt: '2026-11-01T00:00:00Z',
  updatedAt: '2026-11-01T00:00:00Z',
  columns: [
    { id: 'col-done', boardId: 'board-1', name: 'Done', position: 1, statusCategory: 'DONE', createdAt: '', updatedAt: '' },
    { id: 'col-todo', boardId: 'board-1', name: 'To do', position: 0, statusCategory: 'TODO', createdAt: '', updatedAt: '' },
  ],
};

function routeRequest(path: string) {
  if (path === '/api/v1/auth/workspaces') {
    return Promise.resolve([
      { id: 'ws-1', name: 'Personal', slug: 'personal', role: 'OWNER', createdAt: '' },
      { id: 'ws-2', name: 'Team Alpha', slug: 'team-alpha', role: 'MEMBER', createdAt: '' },
    ]);
  }
  if (path === '/api/v1/boards/board-1') return Promise.resolve(BOARD);
  if (path.startsWith('/api/v1/columns/')) return Promise.resolve({ id: 'task-1' });
  return Promise.reject(new Error(`unexpected ${path}`));
}

const nextFrame = () => new Promise((resolve) => requestAnimationFrame(() => resolve(null)));

describe('ChatPanel', () => {
  let root: Root;
  let container: HTMLDivElement;

  const render = async (path = '/boards/board-1') => {
    await act(async () => {
      root.render(
        <MemoryRouter initialEntries={[path]}>
          <ChatPanel />
        </MemoryRouter>,
      );
    });
  };
  const launcher = () => container.querySelector('button[aria-controls="devflow-ai-chat-panel"]') as HTMLButtonElement;
  const panel = () => container.querySelector('#devflow-ai-chat-panel') as HTMLElement;
  const buttonByText = (text: string) =>
    Array.from(container.querySelectorAll('button')).find((button) => button.textContent?.includes(text));
  const openPanel = async () => {
    await act(async () => {
      launcher().click();
      await nextFrame();
    });
  };
  const askSample = async () => {
    await act(async () => {
      buttonByText('Dự án còn bao nhiêu task')?.click();
    });
    const sent = mocks.sendMessage.mock.calls.at(-1)?.[0] as ChatMessageRequest;
    return sent.conversationId;
  };
  const deliver = async (...frames: ChatFrame[]) => {
    await act(async () => {
      frames.forEach((frame) => mocks.onFrame?.(frame));
    });
  };
  const chunk = (conversationId: string, seq: number, content: string): ChatFrame => ({
    conversationId,
    seq,
    type: 'CHUNK',
    content,
    metadata: null,
  });

  beforeEach(() => {
    mocks.status = 'connected';
    mocks.sendMessage.mockClear();
    mocks.cancelStream.mockClear();
    mocks.request.mockReset();
    mocks.request.mockImplementation(routeRequest);
    container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
  });

  afterEach(() => {
    act(() => root.unmount());
    container.remove();
  });

  it('opens a modal drawer, keeps Tab inside it, and restores focus to the launcher on Escape', async () => {
    await render();
    expect(panel().hasAttribute('inert')).toBe(true);
    expect(launcher().getAttribute('aria-expanded')).toBe('false');

    await openPanel();
    expect(panel().hasAttribute('inert')).toBe(false);
    expect(panel().getAttribute('aria-modal')).toBe('true');
    expect(document.activeElement?.id).toBe('devflow-chat-input');
    expect(panel().textContent).toContain('Team Alpha');
    expect(panel().textContent).toContain('Sprint 3');

    const focusable = Array.from(panel().querySelectorAll<HTMLElement>('button:not([disabled]), textarea:not([disabled])'));
    const last = focusable[focusable.length - 1];
    last.focus();
    await act(async () => {
      last.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', bubbles: true }));
    });
    expect(document.activeElement).toBe(focusable[0]);

    await act(async () => {
      document.activeElement?.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
      await nextFrame();
    });
    expect(panel().hasAttribute('inert')).toBe(true);
    expect(document.activeElement).toBe(launcher());
  });

  it('asks about the workspace of the open board and assembles only its own stream in seq order', async () => {
    await render();
    await openPanel();
    const conversationId = await askSample();
    expect(mocks.sendMessage).toHaveBeenCalledWith(
      expect.objectContaining({ workspaceId: 'ws-2', message: 'Dự án còn bao nhiêu task chưa hoàn thành?' }),
    );

    await deliver(
      chunk('another-tab', 0, 'LEAKED '),
      chunk(conversationId, 1, 'tasks left.'),
      chunk(conversationId, 0, 'Two '),
      { conversationId, seq: 2, type: 'END', content: '', metadata: null },
    );

    const log = container.querySelector('[role="log"]') as HTMLElement;
    expect(log.textContent).toContain('Two tasks left.');
    expect(log.textContent).not.toContain('LEAKED');
    expect(container.querySelector('[aria-busy="true"]')).toBeNull();
    expect(container.querySelector('button[aria-label="Gửi tin nhắn"]')).not.toBeNull();
  });

  it('renders task proposals with the board columns in board order', async () => {
    await render();
    await openPanel();
    const conversationId = await askSample();
    await deliver(
      {
        conversationId,
        seq: 0,
        type: 'TASK_PROPOSAL',
        content: 'Dưới đây là gợi ý các task cần làm:',
        metadata: { proposals: [{ title: 'Tạo API thanh toán', description: 'REST endpoint', checklist: ['Viết test'] }] },
      },
      { conversationId, seq: 1, type: 'END', content: '', metadata: null },
    );

    const options = Array.from(container.querySelectorAll('select option')).map((option) => option.textContent);
    expect(options).toEqual(['To do', 'Done']);
    await act(async () => {
      buttonByText('Tạo task')?.click();
    });
    expect(mocks.request).toHaveBeenCalledWith('/api/v1/columns/col-todo/tasks', expect.objectContaining({ method: 'POST' }));
  });

  it('stops an answer on request and ignores its late chunks', async () => {
    await render();
    await openPanel();
    const conversationId = await askSample();
    await deliver(chunk(conversationId, 0, 'Partial '));

    await act(async () => {
      (container.querySelector('button[aria-label="Dừng câu trả lời"]') as HTMLButtonElement).click();
    });
    await deliver(chunk(conversationId, 1, 'late text'));

    expect(mocks.cancelStream).toHaveBeenCalledWith(conversationId);
    expect(container.textContent).toContain('Đã dừng câu trả lời.');
    expect(container.textContent).not.toContain('late text');
  });

  it('turns a dropped connection mid-answer into an error with a working retry', async () => {
    await render();
    await openPanel();
    await askSample();

    mocks.status = 'reconnecting';
    await render();
    expect(container.textContent).toContain('Mất kết nối khi trợ lý đang trả lời');

    mocks.status = 'connected';
    await render();
    await act(async () => {
      buttonByText('Thử lại')?.click();
    });
    expect(mocks.sendMessage).toHaveBeenCalledTimes(2);
    expect(mocks.sendMessage.mock.calls[1][0].message).toBe('Dự án còn bao nhiêu task chưa hoàn thành?');
  });

  it('shows server errors in Vietnamese and offers no retry for access denied', async () => {
    await render();
    await openPanel();
    const conversationId = await askSample();
    await deliver({
      conversationId,
      seq: -1,
      type: 'ERROR',
      content: 'You do not have access to this workspace.',
      metadata: { code: 'WORKSPACE_ACCESS_DENIED' },
    });

    expect(container.textContent).toContain('Bạn không có quyền truy cập workspace này.');
    expect(buttonByText('Thử lại')).toBeUndefined();
  });
});
