import { describe, expect, it } from 'vitest';
import { formatRelativeTime } from './formatRelativeTime';

describe('formatRelativeTime', () => {
  const base = new Date('2026-11-11T10:00:00.000Z');

  it('handles null, undefined, empty, or invalid dates gracefully', () => {
    expect(formatRelativeTime(null, base)).toBe('');
    expect(formatRelativeTime(undefined, base)).toBe('');
    expect(formatRelativeTime('', base)).toBe('');
    expect(formatRelativeTime('not-a-date', base)).toBe('');
  });

  it('returns "Vừa xong" for timestamps less than 45 seconds ago or future clock drift', () => {
    // 10 seconds ago
    const t1 = new Date(base.getTime() - 10_000).toISOString();
    expect(formatRelativeTime(t1, base)).toBe('Vừa xong');

    // 5 seconds into the future (skew)
    const tFuture = new Date(base.getTime() + 5_000).toISOString();
    expect(formatRelativeTime(tFuture, base)).toBe('Vừa xong');
  });

  it('formats minutes correctly', () => {
    const t5m = new Date(base.getTime() - 5 * 60_000).toISOString();
    expect(formatRelativeTime(t5m, base)).toBe('5 phút trước');

    const t59m = new Date(base.getTime() - 59 * 60_000).toISOString();
    expect(formatRelativeTime(t59m, base)).toBe('59 phút trước');
  });

  it('formats hours correctly', () => {
    const t1h = new Date(base.getTime() - 3600_000).toISOString();
    expect(formatRelativeTime(t1h, base)).toBe('1 giờ trước');

    const t23h = new Date(base.getTime() - 23 * 3600_000).toISOString();
    expect(formatRelativeTime(t23h, base)).toBe('23 giờ trước');
  });

  it('formats days correctly', () => {
    const t1d = new Date(base.getTime() - 24 * 3600_000).toISOString();
    expect(formatRelativeTime(t1d, base)).toBe('1 ngày trước');

    const t6d = new Date(base.getTime() - 6 * 24 * 3600_000).toISOString();
    expect(formatRelativeTime(t6d, base)).toBe('6 ngày trước');
  });

  it('formats weeks correctly', () => {
    const t14d = new Date(base.getTime() - 14 * 24 * 3600_000).toISOString();
    expect(formatRelativeTime(t14d, base)).toBe('2 tuần trước');
  });

  it('formats dates older than 30 days', () => {
    // Same year: 60 days ago
    const tSameYear = new Date('2026-08-15T10:00:00.000Z').toISOString();
    expect(formatRelativeTime(tSameYear, base)).toBe('15/08');

    // Previous year
    const tPrevYear = new Date('2025-10-10T10:00:00.000Z').toISOString();
    expect(formatRelativeTime(tPrevYear, base)).toBe('10/10/2025');
  });
});
