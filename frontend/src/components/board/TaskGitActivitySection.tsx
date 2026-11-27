import { useCallback, useEffect, useRef, useState } from 'react';
import clsx from 'clsx';
import { fetchTaskGitActivity } from '../../api/gitciApi';
import { useAuth } from '../../auth/AuthProvider';
import { isSafeGitHubUrl, type TaskGitActivityResponse } from '../../types/gitci';
import {
  AlertCircleIcon,
  ExternalLinkIcon,
  GitBranchIcon,
  GitCommitIcon,
  GitMergeIcon,
  GitPullRequestIcon,
  RefreshIcon,
} from '../common/Icons';
import { LoadingSpinner } from '../common/UiPrimitives';
import { CI_STATUS_META, PR_STATUS_META } from './TaskGitBadges';

interface TaskGitActivitySectionProps {
  workspaceId: string;
  taskId: string;
  refreshVersion?: number;
}

/** Read-only Git & CI drill-down panel inside TaskDetailModal (never touches task edit draft). */
export function TaskGitActivitySection({
  workspaceId,
  taskId,
  refreshVersion = 0,
}: TaskGitActivitySectionProps) {
  const { request } = useAuth();
  const [activity, setActivity] = useState<TaskGitActivityResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const sequenceRef = useRef(0);

  const load = useCallback(async (signal?: AbortSignal) => {
    const seq = ++sequenceRef.current;
    setLoading(true);
    setError(null);
    try {
      const data = await fetchTaskGitActivity(request, workspaceId, taskId, signal);
      if (signal?.aborted || seq !== sequenceRef.current) return;
      setActivity(data);
    } catch {
      if (signal?.aborted || seq !== sequenceRef.current) return;
      setError('Không tải được danh sách hoạt động Git/CI.');
    } finally {
      if (!signal?.aborted && seq === sequenceRef.current) setLoading(false);
    }
  }, [request, taskId, workspaceId]);

  useEffect(() => {
    const controller = new AbortController();
    void load(controller.signal);
    return () => controller.abort();
  }, [load, refreshVersion]);

  const hasData = Boolean(
    activity && (activity.totalCommits > 0 || activity.totalPullRequests > 0 || activity.totalCiRuns > 0),
  );

  return (
    <section
      className="space-y-3 rounded-2xl border border-slate-200/90 bg-slate-50/75 p-4"
      aria-label="Hoạt động Git và CI"
      data-testid="task-git-activity-section"
    >
      <div className="flex items-center justify-between gap-2">
        <h3 className="inline-flex items-center gap-1.5 text-xs font-bold uppercase tracking-wider text-slate-500">
          <GitBranchIcon className="size-3.5 text-slate-400" />
          <span>Hoạt động Git &amp; CI</span>
        </h3>
        <button
          type="button"
          disabled={loading}
          onClick={() => void load()}
          aria-label="Tải lại hoạt động Git và CI"
          className="inline-flex size-7 items-center justify-center rounded-lg text-slate-500 transition hover:bg-white hover:text-indigo-600 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 disabled:opacity-45"
        >
          <RefreshIcon className={clsx('size-3.5', loading && 'animate-spin motion-reduce:animate-none')} />
        </button>
      </div>

      {loading && !activity && (
        <div className="flex items-center gap-2 text-xs text-slate-500" role="status">
          <LoadingSpinner className="size-3.5 text-indigo-600" />
          <span>Đang tải dữ liệu Git/CI…</span>
        </div>
      )}

      {error && (
        <div className="rounded-xl border border-rose-200 bg-rose-50/90 p-2.5 text-xs text-rose-800" role="alert">
          <div className="flex items-start gap-2">
            <AlertCircleIcon className="mt-0.5 size-3.5 shrink-0 text-rose-600" />
            <div className="min-w-0 flex-1">
              <p>{error}</p>
              <button
                type="button"
                onClick={() => void load()}
                className="mt-1.5 font-semibold text-rose-700 underline hover:text-rose-900 focus:outline-none focus-visible:ring-2 focus-visible:ring-rose-500"
              >
                Thử tải lại
              </button>
            </div>
          </div>
        </div>
      )}

      {!loading && !error && !hasData && (
        <p className="text-xs leading-5 text-slate-500">
          Chưa có commit, Pull Request hoặc lần chạy CI nào liên kết với công việc này.
        </p>
      )}

      {activity && hasData && (
        <div className="space-y-3.5 text-xs">
          {activity.ciRuns.length > 0 && (
            <div className="space-y-1.5">
              <p className="font-semibold text-slate-700">
                CI Pipelines ({activity.totalCiRuns})
              </p>
              <ul className="space-y-1.5">
                {activity.ciRuns.map((run) => {
                  const meta = CI_STATUS_META[run.status];
                  const safeUrl = isSafeGitHubUrl(run.url) ? run.url : null;
                  const content = (
                    <>
                      <span className={clsx('shrink-0 rounded px-1.5 py-0.5 text-[10px] font-bold ring-1 ring-inset', meta.className)}>
                        {meta.label}
                      </span>
                      <span className="min-w-0 flex-1 truncate text-slate-700">
                        Run #{run.runId} (lần {run.runAttempt}) · <span className="font-mono-code text-slate-500">{run.commitSha.slice(0, 7)}</span>
                      </span>
                      {safeUrl && <ExternalLinkIcon className="size-3 shrink-0 text-slate-400" />}
                    </>
                  );
                  return (
                    <li key={`${run.repositoryName}:${run.runId}:${run.runAttempt}`}>
                      {safeUrl ? (
                        <a
                          href={safeUrl}
                          target="_blank"
                          rel="noopener noreferrer"
                          className="flex items-center gap-2 rounded-xl bg-white px-2.5 py-1.5 ring-1 ring-inset ring-slate-200/80 transition hover:ring-indigo-300 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500"
                        >
                          {content}
                        </a>
                      ) : (
                        <div className="flex items-center gap-2 rounded-xl bg-white px-2.5 py-1.5 ring-1 ring-inset ring-slate-200/80">
                          {content}
                        </div>
                      )}
                    </li>
                  );
                })}
              </ul>
            </div>
          )}

          {activity.pullRequests.length > 0 && (
            <div className="space-y-1.5">
              <p className="font-semibold text-slate-700">
                Pull Requests ({activity.totalPullRequests})
              </p>
              <ul className="space-y-1.5">
                {activity.pullRequests.map((pr) => {
                  const meta = PR_STATUS_META[pr.status];
                  const safeUrl = isSafeGitHubUrl(pr.url) ? pr.url : null;
                  const content = (
                    <>
                      {pr.status === 'MERGED'
                        ? <GitMergeIcon className="size-3.5 shrink-0 text-violet-600" />
                        : <GitPullRequestIcon className={clsx('size-3.5 shrink-0', pr.status === 'OPEN' ? 'text-emerald-600' : 'text-slate-500')} />}
                      <div className="min-w-0 flex-1">
                        <div className="flex items-center gap-1.5">
                          <span className="font-mono-code font-bold text-slate-900">#{pr.prNumber}</span>
                          <span className={clsx('rounded px-1.5 py-0.5 text-[10px] font-semibold ring-1 ring-inset', meta.className)}>
                            {meta.label}
                          </span>
                        </div>
                        <p className="mt-0.5 truncate text-slate-600" title={pr.title}>{pr.title}</p>
                      </div>
                      {safeUrl && <ExternalLinkIcon className="size-3 shrink-0 text-slate-400" />}
                    </>
                  );
                  return (
                    <li key={`${pr.repositoryName}#${pr.prNumber}`}>
                      {safeUrl ? (
                        <a
                          href={safeUrl}
                          target="_blank"
                          rel="noopener noreferrer"
                          className="flex items-start gap-2 rounded-xl bg-white px-2.5 py-1.5 ring-1 ring-inset ring-slate-200/80 transition hover:ring-indigo-300 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500"
                        >
                          {content}
                        </a>
                      ) : (
                        <div className="flex items-start gap-2 rounded-xl bg-white px-2.5 py-1.5 ring-1 ring-inset ring-slate-200/80">
                          {content}
                        </div>
                      )}
                    </li>
                  );
                })}
              </ul>
            </div>
          )}

          {activity.commits.length > 0 && (
            <div className="space-y-1.5">
              <p className="font-semibold text-slate-700">
                Commits ({activity.totalCommits})
              </p>
              <ul className="space-y-1.5">
                {activity.commits.map((commit) => {
                  const safeUrl = isSafeGitHubUrl(commit.url) ? commit.url : null;
                  const content = (
                    <>
                      <GitCommitIcon className="mt-0.5 size-3.5 shrink-0 text-slate-400" />
                      <div className="min-w-0 flex-1">
                        <div className="flex items-center gap-1.5">
                          <span className="font-mono-code font-semibold text-indigo-700">{commit.shortSha}</span>
                          <span className="truncate text-[10px] text-slate-400">{commit.repositoryName}</span>
                        </div>
                        <p className="mt-0.5 truncate text-slate-700" title={commit.messagePreview}>
                          {commit.messagePreview || 'Commit liên kết'}
                        </p>
                      </div>
                      {safeUrl && <ExternalLinkIcon className="mt-0.5 size-3 shrink-0 text-slate-400" />}
                    </>
                  );
                  return (
                    <li key={`${commit.repositoryName}:${commit.sha}`}>
                      {safeUrl ? (
                        <a
                          href={safeUrl}
                          target="_blank"
                          rel="noopener noreferrer"
                          className="flex items-start gap-2 rounded-xl bg-white px-2.5 py-1.5 ring-1 ring-inset ring-slate-200/80 transition hover:ring-indigo-300 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500"
                        >
                          {content}
                        </a>
                      ) : (
                        <div className="flex items-start gap-2 rounded-xl bg-white px-2.5 py-1.5 ring-1 ring-inset ring-slate-200/80">
                          {content}
                        </div>
                      )}
                    </li>
                  );
                })}
              </ul>
            </div>
          )}
        </div>
      )}
    </section>
  );
}
