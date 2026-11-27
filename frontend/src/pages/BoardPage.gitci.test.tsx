// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as authModule from '../auth/AuthProvider';
import * as boardSyncModule from '../hooks/useBoardSync';
import * as notificationSyncModule from '../hooks/useNotificationSync';
import type { BoardMutationMessage } from '../realtime/boardMutation';
import { ApiError } from '../types/board';
import BoardPage from './BoardPage';

vi.mock('@dnd-kit/react', () => ({
  DragDropProvider: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));
vi.mock('@dnd-kit/react/sortable', () => ({
  isSortable: () => false,
  useSortable: () => ({
    ref: () => undefined,
    handleRef: () => undefined,
    isDragging: false,
    isDropTarget: false,
  }),
}));

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const WS_ID = '11111111-1111-4111-8111-111111111111';
const BOARD_ID = '22222222-2222-4222-8222-222222222222';
const COL_ID = '33333333-3333-4333-8333-333333333333';
const TASK_1 = '44444444-4444-4444-8444-444444444444';
const TASK_2 = '55555555-5555-4555-8555-555555555555';
const SHA = 'd'.repeat(40);

const settle = () => act(() => new Promise<void>((resolve) => setTimeout(resolve, 25)));

function setNativeValue(element: HTMLInputElement, value: string) {
  Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set?.call(element, value);
  element.dispatchEvent(new Event('input', { bubbles: true }));
}

describe('BoardPage Git & CI integration', () => {
  let root: Root | null = null;
  let container: HTMLDivElement | null = null;
  let capturedOnMessage: ((msg: BoardMutationMessage) => void) | null = null;

  beforeEach(() => {
    container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
    capturedOnMessage = null;

    vi.spyOn(notificationSyncModule, 'useNotificationSync').mockImplementation(() => undefined);
    vi.spyOn(boardSyncModule, 'useBoardSync').mockImplementation((options) => {
      capturedOnMessage = options.onMessage;
      return { status: 'connected', retry: vi.fn() };
    });
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

  function setupAuthMock(options?: { failGitSummaries?: boolean; ciStatusRef?: { current: 'FAILED' | 'SUCCESS' } }) {
    const columnFetchCalls: string[] = [];
    const summaryFetchCalls: string[] = [];

    const request = vi.fn(async (path: string) => {
      if (path === '/api/v1/auth/workspaces') {
        return [{ id: WS_ID, name: 'DevFlow', slug: 'devflow', role: 'OWNER', createdAt: '2026-11-27T08:00:00Z' }];
      }
      if (path.startsWith(`/api/v1/auth/workspaces/${WS_ID}/members`)) {
        return { items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 };
      }
      if (path.startsWith(`/api/v1/workspaces/${WS_ID}/boards`)) {
        return {
          items: [{ id: BOARD_ID, workspaceId: WS_ID, name: 'Sprint 4', description: null, archived: false, updatedAt: '2026-11-27T08:00:00Z' }],
          page: 0, size: 20, totalElements: 1, totalPages: 1,
        };
      }
      if (path === `/api/v1/boards/${BOARD_ID}`) {
        return {
          id: BOARD_ID,
          workspaceId: WS_ID,
          name: 'Sprint 4',
          description: null,
          archived: false,
          createdAt: '2026-11-27T08:00:00Z',
          updatedAt: '2026-11-27T08:00:00Z',
          columns: [{ id: COL_ID, boardId: BOARD_ID, name: 'In Progress', position: 0, statusCategory: 'IN_PROGRESS' }],
        };
      }
      if (path.startsWith(`/api/v1/columns/${COL_ID}/tasks`)) {
        columnFetchCalls.push(path);
        return {
          items: [
            { id: TASK_1, taskKey: 'T-026', columnId: COL_ID, title: 'Render Git badges', priority: 'HIGH', assigneeId: null, dueDate: null, position: 0, statusCategory: 'IN_PROGRESS', version: 1 },
            { id: TASK_2, taskKey: 'T-027', columnId: COL_ID, title: 'Clean task without git', priority: 'LOW', assigneeId: null, dueDate: null, position: 1, statusCategory: 'IN_PROGRESS', version: 1 },
          ],
          page: 0, size: 50, totalElements: 2, totalPages: 1,
        };
      }
      if (path.startsWith(`/api/v1/gitci/workspaces/${WS_ID}/task-summaries`)) {
        summaryFetchCalls.push(path);
        if (options?.failGitSummaries) {
          throw new ApiError('GitCI unavailable', 503);
        }
        const status = options?.ciStatusRef?.current ?? 'FAILED';
        return {
          workspaceId: WS_ID,
          items: [
            {
              taskId: TASK_1,
              commitCount: 2,
              latestCommit: {
                sha: SHA,
                shortSha: 'ddddddd',
                messagePreview: 'feat: badges',
                url: `https://github.com/devflow/web/commit/${SHA}`,
                committedAt: '2026-11-27T09:00:00Z',
                repositoryName: 'devflow/web',
              },
              prCount: 1,
              openPrCount: 1,
              primaryPr: {
                prNumber: 26,
                title: 'T-026 PR',
                status: 'OPEN',
                url: 'https://github.com/devflow/web/pull/26',
                sourceBranch: 'feat/t-026',
                updatedAt: '2026-11-27T09:05:00Z',
                repositoryName: 'devflow/web',
              },
              ci: {
                status,
                providerConclusion: status === 'FAILED' ? 'failure' : 'success',
                failedRunCount: status === 'FAILED' ? 1 : 0,
                runId: '501',
                runAttempt: 1,
                commitSha: SHA,
                repositoryName: 'devflow/web',
                url: 'https://github.com/devflow/web/actions/runs/501/attempts/1',
                finishedAt: '2026-11-27T09:06:00Z',
                staleForLatestCommit: false,
              },
            },
          ],
        };
      }
      throw new Error(`Unexpected request ${path}`);
    });

    vi.spyOn(authModule, 'useAuth').mockReturnValue({
      user: { id: 'user-1', email: 'xuan@devflow.vn', fullName: 'Lê Viết Xuân', avatarUrl: null },
      ready: true,
      sessionGeneration: 1,
      sessionEnd: null,
      signIn: vi.fn(),
      signOut: vi.fn(),
      getValidAccessToken: vi.fn(async () => 'token'),
      request: request as unknown as ReturnType<typeof authModule.useAuth>['request'],
    });

    return { columnFetchCalls, summaryFetchCalls };
  }

  function renderBoard() {
    return act(async () => {
      root?.render(
        <MemoryRouter initialEntries={[`/boards/${BOARD_ID}`]}>
          <Routes>
            <Route path="/boards/:boardId" element={<BoardPage />} />
          </Routes>
        </MemoryRouter>,
      );
    });
  }

  it('refreshes badges on CARD_UPDATED(gitActivity) without refetching columns and preserves badges across filters (RT-07, UI-09)', async () => {
    const ciStatusRef = { current: 'FAILED' as 'FAILED' | 'SUCCESS' };
    const { columnFetchCalls, summaryFetchCalls } = setupAuthMock({ ciStatusRef });

    await renderBoard();
    await settle();
    await settle();

    expect(container?.textContent).toContain('CI lỗi');
    expect(container?.textContent).toContain('#26');
    expect(columnFetchCalls).toHaveLength(1);
    expect(summaryFetchCalls).toHaveLength(1);

    // Emit STOMP CARD_UPDATED with changedFields=['gitActivity'] -> updates badge to CI đạt WITHOUT refetching column tasks
    ciStatusRef.current = 'SUCCESS';
    await act(async () => {
      capturedOnMessage?.({
        schemaVersion: 1,
        eventId: 'evt-git-1',
        type: 'CARD_UPDATED',
        boardId: BOARD_ID,
        occurredAt: '2026-11-27T09:10:00Z',
        correlationId: 'corr-1',
        actorId: null,
        data: {
          taskId: TASK_1,
          columnId: COL_ID,
          sourceColumnId: null,
          destinationColumnId: null,
          fromPosition: null,
          toPosition: null,
          affectedColumnIds: [COL_ID],
          changedFields: ['gitActivity'],
          refresh: ['TASK_LISTS', 'TASK_DETAIL'],
        },
      });
    });
    // Wait past 150ms debounce
    await act(() => new Promise<void>((resolve) => setTimeout(resolve, 210)));

    expect(container?.textContent).toContain('CI đạt');
    expect(columnFetchCalls).toHaveLength(1); // Zero structural column refetch!
    expect(summaryFetchCalls).toHaveLength(2);
    expect(summaryFetchCalls[1]).toContain(TASK_1);

    // Filter board by keyword -> matching card retains its badges without triggering extra GitCI fetch
    const searchInput = container?.querySelector<HTMLInputElement>('#board-task-search');
    await act(async () => {
      if (searchInput) setNativeValue(searchInput, 'T-026');
    });
    expect(container?.textContent).toContain('CI đạt');
    expect(container?.textContent).not.toContain('Clean task without git');
    expect(summaryFetchCalls).toHaveLength(2);
  });

  it('keeps Kanban board interactive with non-blocking degraded banner when GitCI returns 503 (OPS-01, OPS-02)', async () => {
    setupAuthMock({ failGitSummaries: true });

    await renderBoard();
    await settle();
    await settle();

    // Kanban cards still render cleanly
    expect(container?.textContent).toContain('Render Git badges');
    expect(container?.textContent).toContain('Clean task without git');

    // Non-blocking degraded banner appears with retry button
    const banner = container?.querySelector('[data-testid="gitci-degraded-banner"]');
    expect(banner).not.toBeNull();
    expect(banner?.textContent).toContain('Bảng Kanban vẫn hoạt động bình thường');
    expect(banner?.querySelector('button')?.textContent).toContain('Thử tải lại');
  });
});
