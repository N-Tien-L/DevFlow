import { describe, expect, it } from 'vitest';
import {
  hasGitOrCiActivity,
  isSafeGitHubUrl,
  parseTaskGitActivity,
  parseTaskGitSummaryBatch,
  type TaskGitSummary,
} from './gitci';

const WS_ID = '11111111-1111-4111-8111-111111111111';
const TASK_ID = '22222222-2222-4222-8222-222222222222';
const SHA40 = 'a'.repeat(40);
const SHA64 = 'b'.repeat(64);

describe('gitci types and client-side validators', () => {
  describe('isSafeGitHubUrl (SEC-10)', () => {
    it('accepts canonical HTTPS GitHub commit, PR, and Actions attempt URLs', () => {
      expect(isSafeGitHubUrl(`https://github.com/devflow/web/commit/${SHA40}`)).toBe(true);
      expect(isSafeGitHubUrl(`https://github.com/devflow/web.api/commit/${SHA64}`)).toBe(true);
      expect(isSafeGitHubUrl('https://github.com/devflow/web/pull/42')).toBe(true);
      expect(isSafeGitHubUrl('https://github.com/devflow/web/actions/runs/99100/attempts/2')).toBe(true);
    });

    it('rejects javascript:, data:, http:, foreign domains, credentials, query strings, fragments, and traversal', () => {
      const invalidUrls = [
        null,
        undefined,
        '',
        'javascript:alert(1)',
        'data:text/html,<script>alert(1)</script>',
        `http://github.com/devflow/web/commit/${SHA40}`,
        'https://evil.example/devflow/web/pull/42',
        'https://github.com.evil.com/devflow/web/pull/42',
        'https://user:pass@github.com/devflow/web/pull/42',
        'https://github.com:8443/devflow/web/pull/42',
        'https://github.com/devflow/web/pull/42?tab=checks',
        'https://github.com/devflow/web/pull/42#discussion',
        'https://github.com/../web/pull/42',
        'https://github.com/devflow/../pull/42',
        'https://github.com/devflow/web/pull/0',
        'https://github.com/devflow/web/commit/shortsha',
        'https://github.com/devflow/web/actions/runs/100',
      ];
      for (const candidate of invalidUrls) {
        expect(isSafeGitHubUrl(candidate)).toBe(false);
      }
    });
  });

  describe('parseTaskGitSummaryBatch', () => {
    it('parses valid batch payloads and strips unsafe URLs to null without crashing', () => {
      const parsed = parseTaskGitSummaryBatch(
        {
          workspaceId: WS_ID,
          items: [
            {
              taskId: TASK_ID,
              commitCount: 2,
              latestCommit: {
                sha: SHA40,
                shortSha: 'aaaaaaa',
                messagePreview: 'feat: badges',
                url: 'javascript:alert(1)',
                committedAt: '2026-11-27T09:00:00Z',
                repositoryName: 'devflow/web',
              },
              prCount: 1,
              openPrCount: 1,
              primaryPr: {
                prNumber: 42,
                title: 'Add badges',
                status: 'OPEN',
                url: 'https://github.com/devflow/web/pull/42',
                sourceBranch: 'feat/t-026',
                updatedAt: '2026-11-27T09:05:00Z',
                repositoryName: 'devflow/web',
              },
              ci: {
                status: 'FAILED',
                providerConclusion: 'failure',
                failedRunCount: 2,
                runId: '991',
                runAttempt: 1,
                commitSha: SHA40,
                repositoryName: 'devflow/web',
                url: 'https://github.com/devflow/web/actions/runs/991/attempts/1',
                finishedAt: '2026-11-27T09:06:00Z',
                staleForLatestCommit: false,
              },
            },
          ],
        },
        WS_ID,
      );

      expect(parsed).toHaveLength(1);
      expect(parsed[0].latestCommit?.url).toBeNull();
      expect(parsed[0].primaryPr?.url).toBe('https://github.com/devflow/web/pull/42');
      expect(parsed[0].ci?.status).toBe('FAILED');
      expect(hasGitOrCiActivity(parsed[0])).toBe(true);
    });

    it('rejects mismatched workspaceId, malformed shapes, negative counts, invalid enums, and oversized payloads', () => {
      expect(parseTaskGitSummaryBatch(null, WS_ID)).toEqual([]);
      expect(parseTaskGitSummaryBatch({ workspaceId: 'other-ws', items: [] }, WS_ID)).toEqual([]);
      expect(
        parseTaskGitSummaryBatch(
          {
            workspaceId: WS_ID,
            items: [
              { taskId: 'not-a-uuid', commitCount: 1, latestCommit: null, prCount: 0, openPrCount: 0, primaryPr: null, ci: null },
              { taskId: TASK_ID, commitCount: -1, latestCommit: null, prCount: 0, openPrCount: 0, primaryPr: null, ci: null },
              { taskId: TASK_ID, commitCount: 0, latestCommit: null, prCount: 0, openPrCount: 0, primaryPr: null, ci: null },
            ],
          },
          WS_ID,
        ),
      ).toEqual([]);

      const oversized = Array.from({ length: 201 }, () => ({
        taskId: TASK_ID,
        commitCount: 1,
        latestCommit: null,
        prCount: 0,
        openPrCount: 0,
        primaryPr: null,
        ci: null,
      }));
      expect(parseTaskGitSummaryBatch({ workspaceId: WS_ID, items: oversized }, WS_ID)).toEqual([]);
    });
  });

  describe('parseTaskGitActivity', () => {
    it('validates workspaceId/taskId scope and filters invalid sub-items cleanly', () => {
      const activity = parseTaskGitActivity(
        {
          workspaceId: WS_ID,
          taskId: TASK_ID,
          summary: null,
          totalCommits: 1,
          commits: [
            {
              sha: SHA40,
              shortSha: 'aaaaaaa',
              messagePreview: 'fix: test',
              url: `https://github.com/devflow/web/commit/${SHA40}`,
              committedAt: '2026-11-27T09:00:00Z',
              repositoryName: 'devflow/web',
            },
            { bad: true },
          ],
          totalPullRequests: 0,
          pullRequests: [],
          totalCiRuns: 1,
          ciRuns: [
            {
              status: 'TIMED_OUT',
              providerConclusion: 'timed_out',
              runId: '100',
              runAttempt: 2,
              commitSha: SHA40,
              repositoryName: 'devflow/web',
              url: 'https://evil.example/runs/100',
              startedAt: null,
              finishedAt: '2026-11-27T09:10:00Z',
            },
          ],
        },
        WS_ID,
        TASK_ID,
      );

      expect(activity).not.toBeNull();
      expect(activity?.commits).toHaveLength(1);
      expect(activity?.ciRuns).toHaveLength(1);
      expect(activity?.ciRuns[0].status).toBe('TIMED_OUT');
      expect(activity?.ciRuns[0].url).toBeNull();
      expect(parseTaskGitActivity({ workspaceId: WS_ID, taskId: TASK_ID }, 'wrong-ws', TASK_ID)).toBeNull();
    });

    it('detects empty summary with hasGitOrCiActivity', () => {
      const empty: TaskGitSummary = {
        taskId: TASK_ID,
        commitCount: 0,
        latestCommit: null,
        prCount: 0,
        openPrCount: 0,
        primaryPr: null,
        ci: null,
      };
      expect(hasGitOrCiActivity(undefined)).toBe(false);
      expect(hasGitOrCiActivity(empty)).toBe(false);
    });
  });
});
