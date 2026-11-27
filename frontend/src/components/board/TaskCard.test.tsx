// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { BoardColumn, TaskListItem } from '../../types/board';
import type { CiStatus, PullRequestStatus, TaskGitSummary } from '../../types/gitci';
import { TaskCard } from './TaskCard';

vi.mock('@dnd-kit/react/sortable', () => ({
  useSortable: () => ({
    ref: () => undefined,
    handleRef: () => undefined,
    isDragging: false,
    isDropTarget: false,
  }),
}));

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const SHA = 'a'.repeat(40);
const baseColumn: BoardColumn = {
  id: 'col-1',
  boardId: 'board-1',
  name: 'In Progress',
  position: 0,
  statusCategory: 'IN_PROGRESS',
  createdAt: '2026-11-27T08:00:00Z',
  updatedAt: '2026-11-27T08:00:00Z',
  tasks: [],
  totalElements: 1,
  totalPages: 1,
  loadedPages: 1,
  complete: true,
  loading: false,
  error: null,
};
const otherColumn: BoardColumn = {
  ...baseColumn,
  id: 'col-2',
  name: 'Done',
  position: 1,
  statusCategory: 'DONE',
};
const baseTask: TaskListItem = {
  id: '22222222-2222-4222-8222-222222222222',
  taskKey: 'T-026',
  columnId: 'col-1',
  title: 'Hiển thị Git & CI badges trên thẻ task',
  priority: 'HIGH',
  assigneeId: null,
  dueDate: null,
  position: 0,
  statusCategory: 'IN_PROGRESS',
  updatedAt: '2026-11-27T08:00:00Z',
};

function makeSummary(overrides: Partial<TaskGitSummary> = {}): TaskGitSummary {
  return {
    taskId: baseTask.id,
    commitCount: 3,
    latestCommit: {
      sha: SHA,
      shortSha: 'aaaaaaa',
      messagePreview: 'feat: render git badges',
      url: `https://github.com/devflow/web/commit/${SHA}`,
      committedAt: '2026-11-27T09:00:00Z',
      repositoryName: 'devflow/web',
    },
    prCount: 2,
    openPrCount: 1,
    primaryPr: {
      prNumber: 42,
      title: 'T-026 Render Git & CI badges',
      status: 'OPEN',
      url: 'https://github.com/devflow/web/pull/42',
      sourceBranch: 'feature/T-026',
      updatedAt: '2026-11-27T09:05:00Z',
      repositoryName: 'devflow/web',
    },
    ci: {
      status: 'FAILED',
      providerConclusion: 'failure',
      failedRunCount: 2,
      runId: '9910',
      runAttempt: 1,
      commitSha: SHA,
      repositoryName: 'devflow/web',
      url: 'https://github.com/devflow/web/actions/runs/9910/attempts/1',
      finishedAt: '2026-11-27T09:06:00Z',
      staleForLatestCommit: false,
    },
    ...overrides,
  };
}

describe('TaskCard & TaskGitBadges', () => {
  let root: Root | null = null;
  let container: HTMLDivElement | null = null;

  beforeEach(() => {
    container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
  });

  afterEach(() => {
    if (root) {
      act(() => root?.unmount());
      root = null;
    }
    container?.remove();
    container = null;
  });

  function renderCard(gitSummary?: TaskGitSummary, options?: { disabled?: boolean; onOpenTask?: ReturnType<typeof vi.fn> }) {
    const onOpenTask = options?.onOpenTask ?? vi.fn();
    act(() => {
      root?.render(
        <TaskCard
          task={baseTask}
          column={baseColumn}
          columns={[baseColumn, otherColumn]}
          gitSummary={gitSummary}
          isAssignedToMe={false}
          index={0}
          disabled={Boolean(options?.disabled)}
          onMoveTask={vi.fn()}
          onOpenTask={onOpenTask}
        />,
      );
    });
    return onOpenTask;
  }

  it('omits Git/CI badge row completely when task has no linked Git or CI data (UI-01)', () => {
    renderCard(undefined);
    expect(container?.querySelector('[data-testid="task-git-badges"]')).toBeNull();
    expect(container?.textContent).not.toContain('CI lỗi');
  });

  it('renders commit, PR, and red CI warning links safely without triggering modal open (UI-02, UI-03, UI-04, UI-07, UI-13)', () => {
    const onOpenTask = renderCard(makeSummary());

    const badgeRow = container?.querySelector('[data-testid="task-git-badges"]');
    expect(badgeRow).not.toBeNull();

    // Never nest <a> inside <button> (HTML5 interactive nesting validity)
    expect(container?.querySelectorAll('button a')).toHaveLength(0);
    expect(container?.querySelectorAll('a button')).toHaveLength(0);

    const links = Array.from(badgeRow?.querySelectorAll('a') ?? []);
    expect(links).toHaveLength(3);

    // Commit link
    expect(links[0].getAttribute('href')).toBe(`https://github.com/devflow/web/commit/${SHA}`);
    expect(links[0].getAttribute('target')).toBe('_blank');
    expect(links[0].getAttribute('rel')).toBe('noopener noreferrer');
    expect(links[0].textContent).toContain('3');
    expect(links[0].getAttribute('title')).toContain('aaaaaaa: feat: render git badges');

    // PR link (#42 +1)
    expect(links[1].getAttribute('href')).toBe('https://github.com/devflow/web/pull/42');
    expect(links[1].textContent).toContain('#42 +1');
    expect(links[1].className).toContain('emerald');

    // CI failure warning badge
    expect(links[2].getAttribute('href')).toBe('https://github.com/devflow/web/actions/runs/9910/attempts/1');
    expect(links[2].getAttribute('data-ci-status')).toBe('FAILED');
    expect(links[2].textContent).toContain('CI lỗi (2)');
    expect(links[2].className).toContain('rose');

    // Clicking any badge link does NOT open TaskDetailModal
    for (const link of links) {
      link.addEventListener('click', (e) => e.preventDefault());
      act(() => {
        link.click();
      });
    }
    expect(onOpenTask).not.toHaveBeenCalled();

    // Clicking task title still opens TaskDetailModal
    const titleButton = container?.querySelector<HTMLButtonElement>('h3 button');
    act(() => {
      titleButton?.click();
    });
    expect(onOpenTask).toHaveBeenCalledTimes(1);
    expect(onOpenTask).toHaveBeenCalledWith(baseTask.id, titleButton);
  });

  it('renders distinct PR states (OPEN, MERGED, CLOSED) and all CI statuses accurately (UI-03, UI-05)', () => {
    const prStates: Array<{ status: PullRequestStatus; tone: string; label: string }> = [
      { status: 'OPEN', tone: 'emerald', label: 'Đang mở' },
      { status: 'MERGED', tone: 'violet', label: 'Đã merge' },
      { status: 'CLOSED', tone: 'slate', label: 'Đã đóng' },
    ];
    for (const { status, tone, label } of prStates) {
      renderCard(makeSummary({
        commitCount: 0,
        latestCommit: null,
        ci: null,
        prCount: 1,
        primaryPr: { ...makeSummary().primaryPr!, status },
      }));
      const prLink = container?.querySelector('[data-testid="task-git-badges"] a');
      expect(prLink?.className).toContain(tone);
      expect(prLink?.getAttribute('title')).toContain(label);
    }

    const ciCases: Array<{ status: CiStatus; expectedLabel: string }> = [
      { status: 'SUCCESS', expectedLabel: 'CI đạt' },
      { status: 'RUNNING', expectedLabel: 'CI đang chạy' },
      { status: 'PENDING', expectedLabel: 'CI chờ chạy' },
      { status: 'TIMED_OUT', expectedLabel: 'CI quá hạn' },
      { status: 'ACTION_REQUIRED', expectedLabel: 'CI cần xử lý' },
      { status: 'CANCELLED', expectedLabel: 'CI đã hủy' },
      { status: 'SKIPPED', expectedLabel: 'CI bỏ qua' },
      { status: 'NEUTRAL', expectedLabel: 'CI khác' },
      { status: 'STALE', expectedLabel: 'CI cũ' },
      { status: 'UNKNOWN', expectedLabel: 'CI khác' },
    ];
    for (const { status, expectedLabel } of ciCases) {
      renderCard(makeSummary({
        commitCount: 0,
        latestCommit: null,
        prCount: 0,
        primaryPr: null,
        ci: { ...makeSummary().ci!, status, failedRunCount: 0 },
      }));
      const ciBadge = container?.querySelector(`[data-ci-status="${status}"]`);
      expect(ciBadge?.textContent).toContain(expectedLabel);
    }
  });

  it('shows stale CI tooltip, degrades unsafe/missing URLs to spans, escapes XSS payloads, and stays clickable when card is disabled/archived (UI-06, UI-12, SEC-09, SEC-10)', () => {
    const xss = '<img src=x onerror="window.__xss=1">';
    renderCard(
      makeSummary({
        latestCommit: {
          ...makeSummary().latestCommit!,
          messagePreview: xss,
          url: 'javascript:alert(1)',
        },
        primaryPr: {
          ...makeSummary().primaryPr!,
          title: xss,
          url: null,
        },
        ci: {
          ...makeSummary().ci!,
          url: 'https://github.com/devflow/web/actions/runs/9910/attempts/1',
          staleForLatestCommit: true,
        },
      }),
      { disabled: true },
    );

    const badgeRow = container?.querySelector('[data-testid="task-git-badges"]');
    // Only the safe CI URL becomes an <a>; commit (unsafe JS URL) & PR (null URL) degrade to non-link <span>
    const links = badgeRow?.querySelectorAll('a') ?? [];
    expect(links).toHaveLength(1);
    expect(links[0].getAttribute('data-ci-status')).toBe('FAILED');
    expect(links[0].getAttribute('title')).toContain('commit mới nhất chưa có CI');

    // No executable <img> or <script> injected into DOM
    expect(container?.querySelector('img[src="x"]')).toBeNull();
    expect(container?.querySelector('script')).toBeNull();
    // Archived/disabled card disables drag handle & move button, while keeping GitHub badge link active
    expect(container?.querySelector<HTMLButtonElement>('button[aria-label^="Kéo để di chuyển"]')?.disabled).toBe(true);
    expect(links[0].getAttribute('href')).toBe('https://github.com/devflow/web/actions/runs/9910/attempts/1');
  });
});
