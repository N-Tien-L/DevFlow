import type { ApiRequest } from './boardApi';
import { mapWithConcurrency } from '../board/boardSyncCoordinator';
import {
  parseTaskGitActivity,
  parseTaskGitSummaryBatch,
  type TaskGitActivityResponse,
  type TaskGitSummary,
} from '../types/gitci';

export const GIT_SUMMARY_BATCH_SIZE = 50;
const GIT_SUMMARY_CONCURRENCY = 3;

export function chunkTaskIds(taskIds: readonly string[], chunkSize = GIT_SUMMARY_BATCH_SIZE): string[][] {
  const unique = [...new Set(taskIds.filter((id) => id.trim().length > 0))];
  const chunks: string[][] = [];
  for (let i = 0; i < unique.length; i += chunkSize) {
    chunks.push(unique.slice(i, i + chunkSize));
  }
  return chunks;
}

/**
 * Fetches Git & CI badge summaries for an arbitrary list of task IDs using bounded
 * idempotent GET chunks (<= 50 UUIDs per request, concurrency 3) so AuthProvider's
 * single-flight 401 refresh + retry applies automatically.
 */
export async function fetchTaskGitSummaries(
  request: ApiRequest,
  workspaceId: string,
  taskIds: readonly string[],
  signal?: AbortSignal,
): Promise<Map<string, TaskGitSummary>> {
  const chunks = chunkTaskIds(taskIds, GIT_SUMMARY_BATCH_SIZE);
  const result = new Map<string, TaskGitSummary>();
  if (!workspaceId || chunks.length === 0) return result;

  await mapWithConcurrency(chunks, GIT_SUMMARY_CONCURRENCY, async (chunk) => {
    if (signal?.aborted) return;
    const query = encodeURIComponent(chunk.join(','));
    const raw = await request<unknown>(
      `/api/v1/gitci/workspaces/${encodeURIComponent(workspaceId)}/task-summaries?taskIds=${query}`,
      { signal },
    );
    if (signal?.aborted) return;
    for (const item of parseTaskGitSummaryBatch(raw, workspaceId)) {
      result.set(item.taskId, item);
    }
  });
  return result;
}

/** Fetches the bounded read-only commit/PR/CI breakdown for a single task modal. */
export async function fetchTaskGitActivity(
  request: ApiRequest,
  workspaceId: string,
  taskId: string,
  signal?: AbortSignal,
): Promise<TaskGitActivityResponse | null> {
  if (!workspaceId || !taskId) return null;
  const raw = await request<unknown>(
    `/api/v1/gitci/workspaces/${encodeURIComponent(workspaceId)}/tasks/${encodeURIComponent(taskId)}/activity?commitsLimit=20&prsLimit=20&ciRunsLimit=10`,
    { signal },
  );
  return parseTaskGitActivity(raw, workspaceId, taskId);
}
