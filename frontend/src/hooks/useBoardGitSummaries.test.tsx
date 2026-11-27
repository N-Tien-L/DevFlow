// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ApiRequest } from '../api/boardApi';
import { ApiError } from '../types/board';
import { useBoardGitSummaries, type UseBoardGitSummariesResult } from './useBoardGitSummaries';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const WS_A = '11111111-1111-4111-8111-111111111111';
const WS_B = '22222222-2222-4222-8222-222222222222';
const BOARD_A = '33333333-3333-4333-8333-333333333333';
const BOARD_B = '44444444-4444-4444-8444-444444444444';
const TASK_1 = '55555555-5555-4555-8555-555555555555';
const TASK_2 = '66666666-6666-4666-8666-666666666666';

function summaryPayload(workspaceId: string, taskId: string, commitCount: number) {
  return {
    workspaceId,
    items: commitCount > 0
      ? [{
        taskId,
        commitCount,
        latestCommit: null,
        prCount: 0,
        openPrCount: 0,
        primaryPr: null,
        ci: null,
      }]
      : [],
  };
}

describe('useBoardGitSummaries', () => {
  let root: Root | null = null;
  let container: HTMLDivElement | null = null;
  let latest: UseBoardGitSummariesResult;

  function Harness(props: {
    request: ApiRequest;
    workspaceId: string;
    boardId?: string;
    sessionGeneration: number;
    enabled: boolean;
  }) {
    latest = useBoardGitSummaries(props);
    return null;
  }

  beforeEach(() => {
    container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
  });

  afterEach(() => {
    vi.useRealTimers();
    if (root) {
      act(() => root?.unmount());
      root = null;
    }
    container?.remove();
    container = null;
  });

  it('loads full summaries, merges subset updates, removes unlinked tasks, and debounces bursts (RT-06, RT-08)', async () => {
    vi.useFakeTimers();
    let mode: 'initial' | 'subset' = 'initial';
    const request = vi.fn(async (path: string) => {
      if (mode === 'initial') {
        expect(path).toContain(TASK_1);
        return summaryPayload(WS_A, TASK_1, 2);
      }
      // Subset returns TASK_2 with 1 commit and omits TASK_1 (unlinked)
      return summaryPayload(WS_A, TASK_2, 1);
    }) as unknown as ApiRequest;

    await act(async () => {
      root?.render(<Harness request={request} workspaceId={WS_A} boardId={BOARD_A} sessionGeneration={1} enabled />);
    });

    await act(async () => {
      latest.refreshAll([TASK_1, TASK_2]);
      await Promise.resolve();
    });
    expect(latest.summariesByTaskId.get(TASK_1)?.commitCount).toBe(2);
    expect(latest.summariesByTaskId.has(TASK_2)).toBe(false);

    // Burst 3 debounced subset calls within 150ms -> coalesced into 1 GET
    mode = 'subset';
    await act(async () => {
      latest.refreshSubset([TASK_1]);
      latest.refreshSubset([TASK_2]);
      latest.refreshSubset([TASK_1, TASK_2]);
      vi.advanceTimersByTime(160);
      await Promise.resolve();
    });

    expect(request).toHaveBeenCalledTimes(2);
    expect(latest.summariesByTaskId.has(TASK_1)).toBe(false);
    expect(latest.summariesByTaskId.get(TASK_2)?.commitCount).toBe(1);
  });

  it('ignores stale responses after board/workspace/session switch and clears state (RT-09, SEC-11)', async () => {
    let resolveSlow!: (value: unknown) => void;
    const request = vi.fn(() => new Promise((resolve) => { resolveSlow = resolve; })) as unknown as ApiRequest;

    await act(async () => {
      root?.render(<Harness request={request} workspaceId={WS_A} boardId={BOARD_A} sessionGeneration={1} enabled />);
    });
    await act(async () => {
      latest.refreshAll([TASK_1]);
    });
    expect(latest.loading).toBe(true);

    // Switch to Board B before Board A response arrives
    await act(async () => {
      root?.render(<Harness request={request} workspaceId={WS_B} boardId={BOARD_B} sessionGeneration={2} enabled />);
    });

    await act(async () => {
      resolveSlow(summaryPayload(WS_A, TASK_1, 9));
      await Promise.resolve();
    });

    expect(latest.summariesByTaskId.size).toBe(0);
    expect(latest.loading).toBe(false);
    expect(latest.error).toBeNull();
  });

  it('preserves stale badges on 503/429 degraded mode and clears map on 401/403/404 (OPS-01, OPS-02, SEC-11)', async () => {
    let failWith: ApiError | null = null;
    const request = vi.fn(async () => {
      if (failWith) throw failWith;
      return summaryPayload(WS_A, TASK_1, 3);
    }) as unknown as ApiRequest;

    await act(async () => {
      root?.render(<Harness request={request} workspaceId={WS_A} boardId={BOARD_A} sessionGeneration={1} enabled />);
    });

    await act(async () => {
      latest.refreshAll([TASK_1]);
      await Promise.resolve();
    });
    expect(latest.summariesByTaskId.get(TASK_1)?.commitCount).toBe(3);

    // 503 preserves existing badges and sets stale=true
    failWith = new ApiError('Service unavailable', 503);
    await act(async () => {
      latest.refreshSubset([TASK_1], { debounced: false });
      await Promise.resolve();
    });
    expect(latest.summariesByTaskId.get(TASK_1)?.commitCount).toBe(3);
    expect(latest.stale).toBe(true);
    expect(latest.error).toContain('Bảng Kanban vẫn hoạt động bình thường');

    // 429 preserves badges and surfaces Retry-After guidance
    failWith = new ApiError('Rate limited', 429, { status: 429, detail: 'Too many', retryAfterSeconds: 12 });
    await act(async () => {
      latest.refreshSubset([TASK_1], { debounced: false });
      await Promise.resolve();
    });
    expect(latest.summariesByTaskId.get(TASK_1)?.commitCount).toBe(3);
    expect(latest.error).toContain('12 giây');

    // 404/401 clears map immediately so unauthorized tenant data never lingers
    failWith = new ApiError('Not found', 404);
    await act(async () => {
      latest.refreshSubset([TASK_1], { debounced: false });
      await Promise.resolve();
    });
    expect(latest.summariesByTaskId.size).toBe(0);
    expect(latest.stale).toBe(false);
  });
});
