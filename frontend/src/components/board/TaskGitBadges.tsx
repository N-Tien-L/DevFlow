import type { MouseEvent, PointerEvent } from 'react';
import clsx from 'clsx';
import {
  hasGitOrCiActivity,
  isSafeGitHubUrl,
  type CiStatus,
  type PullRequestStatus,
  type TaskGitSummary,
} from '../../types/gitci';
import {
  AlertCircleIcon,
  AlertTriangleIcon,
  CheckCircleIcon,
  GitCommitIcon,
  GitMergeIcon,
  GitPullRequestIcon,
  InfoIcon,
} from '../common/Icons';
import { LoadingSpinner } from '../common/UiPrimitives';

export const PR_STATUS_META: Record<PullRequestStatus, { label: string; className: string }> = {
  OPEN: {
    label: 'Đang mở',
    className: 'bg-emerald-50 text-emerald-700 ring-emerald-200/90 hover:bg-emerald-100/80',
  },
  MERGED: {
    label: 'Đã merge',
    className: 'bg-violet-50 text-violet-700 ring-violet-200/90 hover:bg-violet-100/80',
  },
  CLOSED: {
    label: 'Đã đóng',
    className: 'bg-slate-100 text-slate-600 ring-slate-200/90 hover:bg-slate-200/75',
  },
};

export const CI_STATUS_META: Record<
  CiStatus,
  { label: string; className: string; kind: 'danger' | 'success' | 'running' | 'warning' | 'neutral' }
> = {
  FAILED: {
    label: 'CI lỗi',
    className: 'bg-rose-50 text-rose-700 ring-rose-300/90 font-bold hover:bg-rose-100/85',
    kind: 'danger',
  },
  SUCCESS: {
    label: 'CI đạt',
    className: 'bg-emerald-50 text-emerald-700 ring-emerald-200/80 hover:bg-emerald-100/75',
    kind: 'success',
  },
  RUNNING: {
    label: 'CI đang chạy',
    className: 'bg-sky-50 text-sky-700 ring-sky-200/90 hover:bg-sky-100/75',
    kind: 'running',
  },
  PENDING: {
    label: 'CI chờ chạy',
    className: 'bg-sky-50 text-sky-700 ring-sky-200/90 hover:bg-sky-100/75',
    kind: 'running',
  },
  TIMED_OUT: {
    label: 'CI quá hạn',
    className: 'bg-amber-50 text-amber-800 ring-amber-200/90 hover:bg-amber-100/75',
    kind: 'warning',
  },
  ACTION_REQUIRED: {
    label: 'CI cần xử lý',
    className: 'bg-amber-50 text-amber-800 ring-amber-200/90 hover:bg-amber-100/75',
    kind: 'warning',
  },
  CANCELLED: {
    label: 'CI đã hủy',
    className: 'bg-slate-100 text-slate-600 ring-slate-200/90 hover:bg-slate-200/75',
    kind: 'neutral',
  },
  SKIPPED: {
    label: 'CI bỏ qua',
    className: 'bg-slate-100 text-slate-600 ring-slate-200/90 hover:bg-slate-200/75',
    kind: 'neutral',
  },
  NEUTRAL: {
    label: 'CI khác',
    className: 'bg-slate-100 text-slate-600 ring-slate-200/90 hover:bg-slate-200/75',
    kind: 'neutral',
  },
  STALE: {
    label: 'CI cũ',
    className: 'bg-slate-100 text-slate-600 ring-slate-200/90 hover:bg-slate-200/75',
    kind: 'neutral',
  },
  UNKNOWN: {
    label: 'CI khác',
    className: 'bg-slate-100 text-slate-600 ring-slate-200/90 hover:bg-slate-200/75',
    kind: 'neutral',
  },
};

function stopInteraction(event: MouseEvent | PointerEvent) {
  event.stopPropagation();
}

function CiStatusIcon({ kind }: { kind: 'danger' | 'success' | 'running' | 'warning' | 'neutral' }) {
  if (kind === 'danger') return <AlertTriangleIcon className="size-3 shrink-0" />;
  if (kind === 'success') return <CheckCircleIcon className="size-3 shrink-0" />;
  if (kind === 'running') return <LoadingSpinner className="size-3 shrink-0" />;
  if (kind === 'warning') return <AlertCircleIcon className="size-3 shrink-0" />;
  return <InfoIcon className="size-3 shrink-0" />;
}

const BADGE_BASE =
  'inline-flex min-h-6 max-w-full items-center gap-1 rounded-md px-1.5 py-0.5 text-[11px] font-semibold leading-4 ring-1 ring-inset transition focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500';

/** Compact, accessible Git commit, PR, and CI status badges rendered directly on TaskCard. */
export function TaskGitBadges({ summary }: { summary?: TaskGitSummary }) {
  if (!summary || !hasGitOrCiActivity(summary)) return null;

  const { commitCount, latestCommit, prCount, primaryPr, ci } = summary;
  const commitUrl = latestCommit && isSafeGitHubUrl(latestCommit.url) ? latestCommit.url : null;
  const prUrl = primaryPr && isSafeGitHubUrl(primaryPr.url) ? primaryPr.url : null;
  const ciUrl = ci && isSafeGitHubUrl(ci.url) ? ci.url : null;

  const commitTitle = latestCommit
    ? `${latestCommit.shortSha}: ${latestCommit.messagePreview || 'Commit liên kết'} (${latestCommit.repositoryName})${commitCount > 1 ? ` · Tổng ${commitCount} commit` : ''}`
    : `${commitCount} commit liên kết`;

  const commitAria = latestCommit && commitUrl
    ? `${commitCount} commit liên kết — mở commit mới nhất ${latestCommit.shortSha} trên GitHub trong tab mới`
    : `${commitCount} commit liên kết`;

  const prMeta = primaryPr ? PR_STATUS_META[primaryPr.status] : null;
  const prTitle = primaryPr && prMeta
    ? `PR #${primaryPr.prNumber} (${prMeta.label}): ${primaryPr.title} (${primaryPr.repositoryName})${prCount > 1 ? ` · Tổng ${prCount} PR` : ''}`
    : '';
  const prAria = primaryPr && prMeta
    ? `Pull Request #${primaryPr.prNumber} (${prMeta.label})${prCount > 1 ? `, tổng ${prCount} PR` : ''}${prUrl ? ' — mở trên GitHub trong tab mới' : ''}`
    : '';

  const ciMeta = ci ? CI_STATUS_META[ci.status] : null;
  const ciLabel = ci && ciMeta
    ? ci.status === 'FAILED' && ci.failedRunCount > 1
      ? `${ciMeta.label} (${ci.failedRunCount})`
      : ciMeta.label
    : '';
  const ciTitle = ci && ciMeta
    ? `${ciLabel} · Run #${ci.runId} (lần ${ci.runAttempt}) · ${ci.commitSha.slice(0, 7)} (${ci.repositoryName})${ci.staleForLatestCommit ? ' · Kết quả của commit trước; commit mới nhất chưa có CI' : ''}`
    : '';
  const ciAria = ci && ciMeta
    ? `${ciLabel} trên ${ci.repositoryName}${ciUrl ? ' — mở chi tiết trên GitHub Actions trong tab mới' : ''}`
    : '';

  return (
    <div
      className="mt-2.5 flex flex-wrap items-center gap-1.5"
      aria-label="Thông tin Git và CI"
      data-testid="task-git-badges"
    >
      {commitCount > 0 && (
        commitUrl ? (
          <a
            href={commitUrl}
            target="_blank"
            rel="noopener noreferrer"
            onClick={stopInteraction}
            onPointerDown={stopInteraction}
            title={commitTitle}
            aria-label={commitAria}
            className={clsx(
              BADGE_BASE,
              'bg-slate-100 text-slate-700 ring-slate-200/90 hover:bg-indigo-50 hover:text-indigo-700 hover:ring-indigo-200',
            )}
          >
            <GitCommitIcon className="size-3 shrink-0" />
            <span className="tabular-nums">{commitCount}</span>
          </a>
        ) : (
          <span
            title={commitTitle}
            aria-label={commitAria}
            className={clsx(BADGE_BASE, 'bg-slate-100 text-slate-700 ring-slate-200/90')}
          >
            <GitCommitIcon className="size-3 shrink-0" />
            <span className="tabular-nums">{commitCount}</span>
          </span>
        )
      )}

      {prCount > 0 && primaryPr && prMeta && (
        prUrl ? (
          <a
            href={prUrl}
            target="_blank"
            rel="noopener noreferrer"
            onClick={stopInteraction}
            onPointerDown={stopInteraction}
            title={prTitle}
            aria-label={prAria}
            className={clsx(BADGE_BASE, prMeta.className)}
          >
            {primaryPr.status === 'MERGED'
              ? <GitMergeIcon className="size-3 shrink-0" />
              : <GitPullRequestIcon className="size-3 shrink-0" />}
            <span className="tabular-nums truncate">
              #{primaryPr.prNumber}
              {prCount > 1 ? ` +${prCount - 1}` : ''}
            </span>
          </a>
        ) : (
          <span
            title={prTitle}
            aria-label={prAria}
            className={clsx(BADGE_BASE, prMeta.className)}
          >
            {primaryPr.status === 'MERGED'
              ? <GitMergeIcon className="size-3 shrink-0" />
              : <GitPullRequestIcon className="size-3 shrink-0" />}
            <span className="tabular-nums truncate">
              #{primaryPr.prNumber}
              {prCount > 1 ? ` +${prCount - 1}` : ''}
            </span>
          </span>
        )
      )}

      {ci && ciMeta && (
        ciUrl ? (
          <a
            href={ciUrl}
            target="_blank"
            rel="noopener noreferrer"
            onClick={stopInteraction}
            onPointerDown={stopInteraction}
            title={ciTitle}
            aria-label={ciAria}
            data-ci-status={ci.status}
            className={clsx(BADGE_BASE, ciMeta.className)}
          >
            <CiStatusIcon kind={ciMeta.kind} />
            <span className="truncate">{ciLabel}</span>
          </a>
        ) : (
          <span
            title={ciTitle}
            aria-label={ciAria}
            data-ci-status={ci.status}
            className={clsx(BADGE_BASE, ciMeta.className)}
          >
            <CiStatusIcon kind={ciMeta.kind} />
            <span className="truncate">{ciLabel}</span>
          </span>
        )
      )}
    </div>
  );
}
