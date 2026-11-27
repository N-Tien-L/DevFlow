// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as authModule from '../../auth/AuthProvider';
import { ApiError, type TaskDetail } from '../../types/board';
import { TaskDetailModal } from './TaskDetailModal';
import { TaskGitActivitySection } from './TaskGitActivitySection';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const WS_ID = '11111111-1111-4111-8111-111111111111';
const TASK_ID = '22222222-2222-4222-8222-222222222222';
const SHA = 'c'.repeat(40);

const baseTask: TaskDetail = {
  id: TASK_ID,
  taskKey: 'T-026',
  boardId: 'board-1',
  workspaceId: WS_ID,
  columnId: 'col-1',
  title: 'Kiểm thử modal Git & CI',
  description: 'Mô tả ban đầu',
  priority: 'MEDIUM',
  assigneeId: null,
  dueDate: null,
  position: 0,
  statusCategory: 'IN_PROGRESS',
  createdAt: '2026-11-27T08:00:00Z',
  updatedAt: '2026-11-27T08:00:00Z',
  version: 1,
};

const sampleActivity = (ciStatus: 'FAILED' | 'SUCCESS') => ({
  workspaceId: WS_ID,
  taskId: TASK_ID,
  summary: null,
  totalCommits: 2,
  commits: [
    {
      sha: SHA,
      shortSha: 'ccccccc',
      messagePreview: 'fix: resolve lint error',
      url: `https://github.com/devflow/web/commit/${SHA}`,
      committedAt: '2026-11-27T09:00:00Z',
      repositoryName: 'devflow/web',
    },
  ],
  totalPullRequests: 1,
  pullRequests: [
    {
      prNumber: 77,
      title: 'T-026 Display Git badges',
      status: 'OPEN',
      url: 'https://github.com/devflow/web/pull/77',
      sourceBranch: 'feature/T-026',
      updatedAt: '2026-11-27T09:05:00Z',
      repositoryName: 'devflow/web',
    },
  ],
  totalCiRuns: 1,
  ciRuns: [
    {
      status: ciStatus,
      providerConclusion: ciStatus === 'FAILED' ? 'failure' : 'success',
      runId: '4001',
      runAttempt: 2,
      commitSha: SHA,
      repositoryName: 'devflow/web',
      url: 'https://github.com/devflow/web/actions/runs/4001/attempts/2',
      startedAt: '2026-11-27T09:06:00Z',
      finishedAt: '2026-11-27T09:07:00Z',
    },
  ],
});

function setNativeValue(element: HTMLInputElement | HTMLTextAreaElement, value: string) {
  const proto = element instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
  Object.getOwnPropertyDescriptor(proto, 'value')?.set?.call(element, value);
  element.dispatchEvent(new Event('input', { bubbles: true }));
}

const settle = () => act(() => new Promise<void>((resolve) => setTimeout(resolve, 15)));

describe('TaskGitActivitySection & TaskDetailModal live refresh', () => {
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

  it('renders commits, PRs, CI runs with safe GitHub links, handles errors and manual retry (UI-10)', async () => {
    let failFirst = true;
    mockAuth(async () => {
      if (failFirst) {
        failFirst = false;
        throw new ApiError('Temporary failure', 503);
      }
      return sampleActivity('FAILED');
    });

    await act(async () => {
      root?.render(<TaskGitActivitySection workspaceId={WS_ID} taskId={TASK_ID} />);
    });
    await settle();

    expect(container?.querySelector('[role="alert"]')?.textContent).toContain('Không tải được danh sách hoạt động Git/CI.');

    // Click retry -> renders full commit/PR/CI breakdown
    const retryBtn = Array.from(container?.querySelectorAll('button') ?? []).find((b) => b.textContent?.includes('Thử tải lại'));
    await act(async () => {
      retryBtn?.click();
    });
    await settle();

    const section = container?.querySelector('[data-testid="task-git-activity-section"]');
    expect(section?.textContent).toContain('CI Pipelines (1)');
    expect(section?.textContent).toContain('Pull Requests (1)');
    expect(section?.textContent).toContain('Commits (2)');
    expect(section?.textContent).toContain('CI lỗi');
    expect(section?.textContent).toContain('#77');
    expect(section?.textContent).toContain('ccccccc');

    const links = Array.from(section?.querySelectorAll('a') ?? []);
    expect(links).toHaveLength(3);
    for (const link of links) {
      expect(link.getAttribute('target')).toBe('_blank');
      expect(link.getAttribute('rel')).toBe('noopener noreferrer');
    }
  });

  it('refreshes Git & CI section on gitActivityRefreshVersion without clobbering unsaved task or comment drafts (UI-11)', async () => {
    let activityCalls = 0;
    mockAuth(async (path) => {
      if (path === `/api/v1/tasks/${TASK_ID}`) return baseTask;
      if (path.includes('/comments')) return { items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 };
      if (path.includes('/members')) return { items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 };
      if (path.includes('/activity')) {
        activityCalls += 1;
        return sampleActivity(activityCalls === 1 ? 'FAILED' : 'SUCCESS');
      }
      throw new Error(`Unexpected ${path}`);
    });

    await act(async () => {
      root?.render(
        <TaskDetailModal
          taskId={TASK_ID}
          canEdit
          gitActivityRefreshVersion={0}
          onClose={vi.fn()}
          onSaved={vi.fn()}
        />,
      );
    });
    await settle();

    expect(container?.querySelector('[data-testid="task-git-activity-section"]')?.textContent).toContain('CI lỗi');

    // User starts typing unsaved edits in title and comment box
    const titleInput = container?.querySelector('input[required]') as HTMLInputElement;
    const commentBox = container?.querySelector('textarea[placeholder*="Viết bình luận"]') as HTMLTextAreaElement;
    await act(async () => {
      setNativeValue(titleInput, 'Bản nháp tiêu đề chưa lưu');
      if (commentBox) setNativeValue(commentBox, 'Bình luận đang gõ dở');
    });

    // Realtime CARD_UPDATED(gitActivity) increments gitActivityRefreshVersion
    await act(async () => {
      root?.render(
        <TaskDetailModal
          taskId={TASK_ID}
          canEdit
          gitActivityRefreshVersion={1}
          onClose={vi.fn()}
          onSaved={vi.fn()}
        />,
      );
    });
    await settle();

    // Git & CI panel updated to CI đạt while unsaved drafts stay completely intact
    expect(container?.querySelector('[data-testid="task-git-activity-section"]')?.textContent).toContain('CI đạt');
    expect(titleInput.value).toBe('Bản nháp tiêu đề chưa lưu');
    if (commentBox) expect(commentBox.value).toBe('Bình luận đang gõ dở');
  });
});
