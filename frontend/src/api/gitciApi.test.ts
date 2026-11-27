import { describe, expect, it, vi } from 'vitest';
import type { ApiRequest } from './boardApi';
import { chunkTaskIds, fetchTaskGitActivity, fetchTaskGitSummaries, GIT_SUMMARY_BATCH_SIZE } from './gitciApi';

const WS_ID = '11111111-1111-4111-8111-111111111111';

function uuid(index: number): string {
  return `00000000-0000-4000-8000-${String(index).padStart(12, '0')}`;
}

describe('gitciApi', () => {
  it('deduplicates, drops blank IDs, and splits into chunks of 50 (OPS-06)', () => {
    const ids = Array.from({ length: 130 }, (_, i) => uuid(i + 1));
    ids.push(uuid(1), '   ', '');

    const chunks = chunkTaskIds(ids);
    expect(GIT_SUMMARY_BATCH_SIZE).toBe(50);
    expect(chunks.map((c) => c.length)).toEqual([50, 50, 30]);
  });

  it('fetches 130 tasks in 3 GET chunks with concurrency <= 3 and merges valid summaries', async () => {
    const ids = Array.from({ length: 130 }, (_, i) => uuid(i + 1));
    let active = 0;
    let maxActive = 0;
    const calledUrls: string[] = [];

    const request = vi.fn(async (path: string) => {
      calledUrls.push(path);
      active += 1;
      maxActive = Math.max(maxActive, active);
      await new Promise((resolve) => setTimeout(resolve, 10));
      active -= 1;

      const parsedQuery = new URLSearchParams(path.split('?')[1]);
      const chunkIds = (parsedQuery.get('taskIds') ?? '').split(',');
      return {
        workspaceId: WS_ID,
        items: [
          {
            taskId: chunkIds[0],
            commitCount: 1,
            latestCommit: null,
            prCount: 0,
            openPrCount: 0,
            primaryPr: null,
            ci: null,
          },
        ],
      };
    }) as unknown as ApiRequest;

    const map = await fetchTaskGitSummaries(request, WS_ID, ids);

    expect(calledUrls).toHaveLength(3);
    expect(maxActive).toBeLessThanOrEqual(3);
    expect(map.size).toBe(3);
    expect(map.get(uuid(1))?.commitCount).toBe(1);
    expect(map.get(uuid(51))?.commitCount).toBe(1);
    expect(map.get(uuid(101))?.commitCount).toBe(1);
  });

  it('skips network calls when workspaceId or taskIds is empty and honors AbortSignal', async () => {
    const request = vi.fn() as unknown as ApiRequest;
    expect((await fetchTaskGitSummaries(request, '', [uuid(1)])).size).toBe(0);
    expect((await fetchTaskGitSummaries(request, WS_ID, [])).size).toBe(0);
    expect(request).not.toHaveBeenCalled();

    const controller = new AbortController();
    controller.abort();
    expect((await fetchTaskGitSummaries(request, WS_ID, [uuid(1)], controller.signal)).size).toBe(0);
    expect(request).not.toHaveBeenCalled();
  });

  it('fetches single-task activity with bounded default limits', async () => {
    const taskId = uuid(7);
    const request = vi.fn(async () => ({
      workspaceId: WS_ID,
      taskId,
      summary: null,
      totalCommits: 0,
      commits: [],
      totalPullRequests: 0,
      pullRequests: [],
      totalCiRuns: 0,
      ciRuns: [],
    })) as unknown as ApiRequest;

    const result = await fetchTaskGitActivity(request, WS_ID, taskId);
    expect(result?.taskId).toBe(taskId);
    expect(request).toHaveBeenCalledWith(
      `/api/v1/gitci/workspaces/${WS_ID}/tasks/${taskId}/activity?commitsLimit=20&prsLimit=20&ciRunsLimit=10`,
      { signal: undefined },
    );
  });
});
