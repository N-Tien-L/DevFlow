import { afterEach, describe, expect, it } from 'vitest';
import { ApiError } from '../types/board';
import { captureInvitationToken, clearInvitationToken, invitationFailure, isFinalFailure } from './invitation';

const problem = (status: number, code?: string, extra: Record<string, unknown> = {}) =>
  new ApiError('server detail', status, { status, code, ...extra }, false);

describe('invitationFailure', () => {
  it.each([
    ['INVITATION_INVALID', 400, 'invalid'],
    ['INVITATION_EXPIRED', 410, 'expired'],
    ['INVITATION_EMAIL_MISMATCH', 403, 'emailMismatch'],
  ])('maps %s (%i) to %s', (code, status, kind) => {
    expect(invitationFailure(problem(status, code))).toEqual({ kind });
  });

  it('falls back to the status when the body has no code', () => {
    expect(invitationFailure(problem(400))).toEqual({ kind: 'invalid' });
    expect(invitationFailure(problem(410))).toEqual({ kind: 'expired' });
  });

  it('does not treat a code-less 403 as an email mismatch', () => {
    expect(invitationFailure(problem(403))).toEqual({ kind: 'failed' });
  });

  it('carries Retry-After for rate limiting, with a default', () => {
    expect(invitationFailure(problem(429, 'RATE_LIMIT_EXCEEDED', { retryAfterSeconds: 42 }))).toEqual({ kind: 'rateLimited', retryAfterSeconds: 42 });
    expect(invitationFailure(problem(429, 'RATE_LIMIT_EXCEEDED'))).toEqual({ kind: 'rateLimited', retryAfterSeconds: 60 });
  });

  it('reports network, timeout and 5xx failures as uncertain', () => {
    expect(invitationFailure(new ApiError('offline', 0, undefined, true))).toEqual({ kind: 'uncertain' });
    expect(invitationFailure(problem(503))).toEqual({ kind: 'uncertain' });
    expect(invitationFailure(new Error('boom'))).toEqual({ kind: 'failed' });
  });

  it('only drops the token for definitive answers', () => {
    expect(isFinalFailure({ kind: 'invalid' })).toBe(true);
    expect(isFinalFailure({ kind: 'expired' })).toBe(true);
    expect(isFinalFailure({ kind: 'emailMismatch' })).toBe(false);
    expect(isFinalFailure({ kind: 'uncertain' })).toBe(false);
    expect(isFinalFailure({ kind: 'rateLimited', retryAfterSeconds: 1 })).toBe(false);
  });
});

describe('invitation token memory', () => {
  afterEach(() => clearInvitationToken());

  it('keeps the token from the URL for a later visit without it', () => {
    expect(captureInvitationToken('?token=%20abc%20')).toBe('abc');
    expect(captureInvitationToken('')).toBe('abc');
  });

  it('prefers a newer token from the URL', () => {
    captureInvitationToken('?token=old');
    expect(captureInvitationToken('?token=new')).toBe('new');
  });

  it('ignores oversized tokens and clears on demand', () => {
    expect(captureInvitationToken(`?token=${'x'.repeat(129)}`)).toBeNull();
    captureInvitationToken('?token=abc');
    clearInvitationToken();
    expect(captureInvitationToken('')).toBeNull();
  });
});
