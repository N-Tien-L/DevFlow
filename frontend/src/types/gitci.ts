export const PULL_REQUEST_STATUSES = ['OPEN', 'MERGED', 'CLOSED'] as const;
export type PullRequestStatus = typeof PULL_REQUEST_STATUSES[number];

export const CI_STATUSES = [
  'PENDING',
  'RUNNING',
  'SUCCESS',
  'FAILED',
  'CANCELLED',
  'SKIPPED',
  'TIMED_OUT',
  'ACTION_REQUIRED',
  'NEUTRAL',
  'STALE',
  'UNKNOWN',
] as const;
export type CiStatus = typeof CI_STATUSES[number];

export interface TaskGitCommitSummary {
  sha: string;
  shortSha: string;
  messagePreview: string;
  url: string | null;
  committedAt: string;
  repositoryName: string;
}

export interface TaskGitPrSummary {
  prNumber: number;
  title: string;
  status: PullRequestStatus;
  url: string | null;
  sourceBranch: string;
  updatedAt: string;
  repositoryName: string;
}

export interface TaskGitCiSummary {
  status: CiStatus;
  providerConclusion: string | null;
  failedRunCount: number;
  runId: string;
  runAttempt: number;
  commitSha: string;
  repositoryName: string;
  url: string | null;
  finishedAt: string | null;
  staleForLatestCommit: boolean;
}

export interface TaskGitSummary {
  taskId: string;
  commitCount: number;
  latestCommit: TaskGitCommitSummary | null;
  prCount: number;
  openPrCount: number;
  primaryPr: TaskGitPrSummary | null;
  ci: TaskGitCiSummary | null;
}

export interface TaskGitCiRunItem {
  status: CiStatus;
  providerConclusion: string | null;
  runId: string;
  runAttempt: number;
  commitSha: string;
  repositoryName: string;
  url: string | null;
  startedAt: string | null;
  finishedAt: string | null;
}

export interface TaskGitActivityResponse {
  workspaceId: string;
  taskId: string;
  summary: TaskGitSummary | null;
  totalCommits: number;
  commits: TaskGitCommitSummary[];
  totalPullRequests: number;
  pullRequests: TaskGitPrSummary[];
  totalCiRuns: number;
  ciRuns: TaskGitCiRunItem[];
}

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const SAFE_GITHUB_PATH_RE =
  /^\/([A-Za-z0-9_.-]+)\/([A-Za-z0-9_.-]+)\/(commit\/([0-9a-f]{40}|[0-9a-f]{64})|pull\/[1-9][0-9]*|actions\/runs\/[1-9][0-9]*\/attempts\/[1-9][0-9]*)$/;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isNonNegativeInt(value: unknown): value is number {
  return typeof value === 'number' && Number.isInteger(value) && value >= 0;
}

function isPositiveInt(value: unknown): value is number {
  return typeof value === 'number' && Number.isInteger(value) && value > 0;
}

function isPullRequestStatus(value: unknown): value is PullRequestStatus {
  return typeof value === 'string' && PULL_REQUEST_STATUSES.includes(value as PullRequestStatus);
}

function isCiStatus(value: unknown): value is CiStatus {
  return typeof value === 'string' && CI_STATUSES.includes(value as CiStatus);
}

/**
 * Defense-in-depth client allowlist ensuring external badge links strictly target
 * HTTPS github.com commit, PR, or Actions run-attempt paths without credentials or queries.
 */
export function isSafeGitHubUrl(raw: unknown): raw is string {
  if (typeof raw !== 'string' || raw.length === 0 || raw.length > 2048) return false;
  let parsed: URL;
  try {
    parsed = new URL(raw);
  } catch {
    return false;
  }
  if (
    parsed.protocol !== 'https:' ||
    parsed.hostname.toLowerCase() !== 'github.com' ||
    parsed.port !== '' ||
    parsed.username !== '' ||
    parsed.password !== '' ||
    parsed.search !== '' ||
    parsed.hash !== ''
  ) {
    return false;
  }
  const match = SAFE_GITHUB_PATH_RE.exec(parsed.pathname);
  if (!match) return false;
  const [, owner, repo] = match;
  if (owner === '.' || owner === '..' || repo === '.' || repo === '..') return false;
  return true;
}

export function hasGitOrCiActivity(summary: TaskGitSummary | undefined): boolean {
  return Boolean(summary && (summary.commitCount > 0 || summary.prCount > 0 || summary.ci !== null));
}

function parseCommit(raw: unknown): TaskGitCommitSummary | null {
  if (!isRecord(raw)) return null;
  if (
    typeof raw.sha !== 'string' ||
    typeof raw.shortSha !== 'string' ||
    typeof raw.messagePreview !== 'string' ||
    typeof raw.committedAt !== 'string' ||
    typeof raw.repositoryName !== 'string'
  ) {
    return null;
  }
  return {
    sha: raw.sha,
    shortSha: raw.shortSha,
    messagePreview: raw.messagePreview,
    url: isSafeGitHubUrl(raw.url) ? raw.url : null,
    committedAt: raw.committedAt,
    repositoryName: raw.repositoryName,
  };
}

function parsePr(raw: unknown): TaskGitPrSummary | null {
  if (!isRecord(raw)) return null;
  if (
    !isPositiveInt(raw.prNumber) ||
    typeof raw.title !== 'string' ||
    !isPullRequestStatus(raw.status) ||
    typeof raw.sourceBranch !== 'string' ||
    typeof raw.updatedAt !== 'string' ||
    typeof raw.repositoryName !== 'string'
  ) {
    return null;
  }
  return {
    prNumber: raw.prNumber,
    title: raw.title,
    status: raw.status,
    url: isSafeGitHubUrl(raw.url) ? raw.url : null,
    sourceBranch: raw.sourceBranch,
    updatedAt: raw.updatedAt,
    repositoryName: raw.repositoryName,
  };
}

function parseCi(raw: unknown): TaskGitCiSummary | null {
  if (!isRecord(raw)) return null;
  if (
    !isCiStatus(raw.status) ||
    !isNonNegativeInt(raw.failedRunCount) ||
    typeof raw.runId !== 'string' ||
    !isPositiveInt(raw.runAttempt) ||
    typeof raw.commitSha !== 'string' ||
    typeof raw.repositoryName !== 'string' ||
    typeof raw.staleForLatestCommit !== 'boolean'
  ) {
    return null;
  }
  return {
    status: raw.status,
    providerConclusion: typeof raw.providerConclusion === 'string' ? raw.providerConclusion : null,
    failedRunCount: raw.failedRunCount,
    runId: raw.runId,
    runAttempt: raw.runAttempt,
    commitSha: raw.commitSha,
    repositoryName: raw.repositoryName,
    url: isSafeGitHubUrl(raw.url) ? raw.url : null,
    finishedAt: typeof raw.finishedAt === 'string' ? raw.finishedAt : null,
    staleForLatestCommit: raw.staleForLatestCommit,
  };
}

function parseCiRunItem(raw: unknown): TaskGitCiRunItem | null {
  if (!isRecord(raw)) return null;
  if (
    !isCiStatus(raw.status) ||
    typeof raw.runId !== 'string' ||
    !isPositiveInt(raw.runAttempt) ||
    typeof raw.commitSha !== 'string' ||
    typeof raw.repositoryName !== 'string'
  ) {
    return null;
  }
  return {
    status: raw.status,
    providerConclusion: typeof raw.providerConclusion === 'string' ? raw.providerConclusion : null,
    runId: raw.runId,
    runAttempt: raw.runAttempt,
    commitSha: raw.commitSha,
    repositoryName: raw.repositoryName,
    url: isSafeGitHubUrl(raw.url) ? raw.url : null,
    startedAt: typeof raw.startedAt === 'string' ? raw.startedAt : null,
    finishedAt: typeof raw.finishedAt === 'string' ? raw.finishedAt : null,
  };
}

function parseSummary(raw: unknown): TaskGitSummary | null {
  if (!isRecord(raw)) return null;
  if (
    typeof raw.taskId !== 'string' ||
    !UUID_RE.test(raw.taskId) ||
    !isNonNegativeInt(raw.commitCount) ||
    !isNonNegativeInt(raw.prCount) ||
    !isNonNegativeInt(raw.openPrCount)
  ) {
    return null;
  }
  const latestCommit = raw.latestCommit == null ? null : parseCommit(raw.latestCommit);
  const primaryPr = raw.primaryPr == null ? null : parsePr(raw.primaryPr);
  const ci = raw.ci == null ? null : parseCi(raw.ci);
  if (raw.commitCount === 0 && raw.prCount === 0 && ci === null) return null;
  return {
    taskId: raw.taskId,
    commitCount: raw.commitCount,
    latestCommit,
    prCount: raw.prCount,
    openPrCount: raw.openPrCount,
    primaryPr,
    ci,
  };
}

/** Validates an untrusted batch payload from /api/v1/gitci/workspaces/{ws}/task-summaries. */
export function parseTaskGitSummaryBatch(raw: unknown, expectedWorkspaceId?: string): TaskGitSummary[] {
  if (!isRecord(raw) || typeof raw.workspaceId !== 'string' || !Array.isArray(raw.items)) return [];
  if (expectedWorkspaceId && raw.workspaceId !== expectedWorkspaceId) return [];
  if (raw.items.length > 200) return [];
  const parsed: TaskGitSummary[] = [];
  for (const item of raw.items) {
    const summary = parseSummary(item);
    if (summary) parsed.push(summary);
  }
  return parsed;
}

/** Validates an untrusted single-task activity payload for TaskDetailModal. */
export function parseTaskGitActivity(
  raw: unknown,
  expectedWorkspaceId?: string,
  expectedTaskId?: string,
): TaskGitActivityResponse | null {
  if (!isRecord(raw)) return null;
  if (
    typeof raw.workspaceId !== 'string' ||
    typeof raw.taskId !== 'string' ||
    !isNonNegativeInt(raw.totalCommits) ||
    !isNonNegativeInt(raw.totalPullRequests) ||
    !isNonNegativeInt(raw.totalCiRuns) ||
    !Array.isArray(raw.commits) ||
    !Array.isArray(raw.pullRequests) ||
    !Array.isArray(raw.ciRuns)
  ) {
    return null;
  }
  if (expectedWorkspaceId && raw.workspaceId !== expectedWorkspaceId) return null;
  if (expectedTaskId && raw.taskId !== expectedTaskId) return null;
  return {
    workspaceId: raw.workspaceId,
    taskId: raw.taskId,
    summary: raw.summary == null ? null : parseSummary(raw.summary),
    totalCommits: raw.totalCommits,
    commits: raw.commits.map(parseCommit).filter((c): c is TaskGitCommitSummary => c !== null),
    totalPullRequests: raw.totalPullRequests,
    pullRequests: raw.pullRequests.map(parsePr).filter((p): p is TaskGitPrSummary => p !== null),
    totalCiRuns: raw.totalCiRuns,
    ciRuns: raw.ciRuns.map(parseCiRunItem).filter((r): r is TaskGitCiRunItem => r !== null),
  };
}
