import { describe, expect, it } from 'vitest';
import { ApiError } from '../types/board';
import { forgotPasswordFailure, isFinalResetFailure, resetPasswordFailure } from './passwordReset';

const problem = (status: number, code?: string, extra: Record<string, unknown> = {}) =>
  new ApiError('server detail', status, { status, code, ...extra }, false);

describe('forgotPasswordFailure', () => {
  it('asks for a new Turnstile challenge when the token is missing or rejected', () => {
    expect(forgotPasswordFailure(problem(400, 'BOT_CHALLENGE_FAILED'))).toEqual({ kind: 'botChallenge' });
    expect(forgotPasswordFailure(problem(400, 'VALIDATION_FAILED', { errors: { turnstileToken: 'Security check token is too long' } })))
      .toEqual({ kind: 'botChallenge' });
  });

  it('waits out rate limits and an unavailable bot check, honouring Retry-After', () => {
    expect(forgotPasswordFailure(problem(429, 'RATE_LIMIT_EXCEEDED', { retryAfterSeconds: 42 }))).toEqual({ kind: 'wait', retryAfterSeconds: 42 });
    expect(forgotPasswordFailure(problem(429, 'RATE_LIMIT_EXCEEDED'))).toEqual({ kind: 'wait', retryAfterSeconds: 60 });
    expect(forgotPasswordFailure(problem(503, 'BOT_PROTECTION_UNAVAILABLE', { retryAfterSeconds: 30 }))).toEqual({ kind: 'wait', retryAfterSeconds: 30 });
  });

  it('flags a server-rejected email and falls back for everything else', () => {
    expect(forgotPasswordFailure(problem(400, 'VALIDATION_FAILED', { errors: { email: 'Invalid email format' } }))).toEqual({ kind: 'email' });
    expect(forgotPasswordFailure(problem(500))).toEqual({ kind: 'failed' });
    expect(forgotPasswordFailure(new ApiError('offline', 0))).toEqual({ kind: 'failed' });
    expect(forgotPasswordFailure(new Error('boom'))).toEqual({ kind: 'failed' });
  });
});

describe('resetPasswordFailure', () => {
  it.each([
    ['RESET_TOKEN_INVALID', 400, 'invalid'],
    ['RESET_TOKEN_EXPIRED', 410, 'expired'],
  ])('maps %s (%i) to %s', (code, status, kind) => {
    expect(resetPasswordFailure(problem(status, code))).toEqual({ kind });
  });

  it('treats a code-less 410 as expired but not a code-less 400 as a dead link', () => {
    expect(resetPasswordFailure(problem(410))).toEqual({ kind: 'expired' });
    // e.g. "Request body is missing or malformed": our bug, not the user's link.
    expect(resetPasswordFailure(problem(400))).toEqual({ kind: 'failed' });
  });

  it('separates a malformed token from a rejected password', () => {
    expect(resetPasswordFailure(problem(400, 'VALIDATION_FAILED', { errors: { token: 'Reset token is malformed' } }))).toEqual({ kind: 'invalid' });
    expect(resetPasswordFailure(problem(400, 'VALIDATION_FAILED', { errors: { newPassword: 'Password must not exceed 72 bytes' } }))).toEqual({ kind: 'password' });
  });

  it('carries Retry-After for rate limiting, with a default', () => {
    expect(resetPasswordFailure(problem(429, 'RATE_LIMIT_EXCEEDED', { retryAfterSeconds: 9 }))).toEqual({ kind: 'rateLimited', retryAfterSeconds: 9 });
    expect(resetPasswordFailure(problem(429, 'RATE_LIMIT_EXCEEDED'))).toEqual({ kind: 'rateLimited', retryAfterSeconds: 60 });
  });

  it('reports network, timeout and 5xx failures as uncertain', () => {
    expect(resetPasswordFailure(new ApiError('offline', 0))).toEqual({ kind: 'uncertain' });
    expect(resetPasswordFailure(problem(502))).toEqual({ kind: 'uncertain' });
    expect(resetPasswordFailure(new Error('boom'))).toEqual({ kind: 'failed' });
  });

  it('reports an unreadable 2xx as unconfirmed rather than failed', () => {
    expect(resetPasswordFailure(new ApiError('bad body', 200, undefined, true))).toEqual({ kind: 'unconfirmed' });
    expect(resetPasswordFailure(new ApiError('bad body', 204))).toEqual({ kind: 'unconfirmed' });
  });

  it('only drops the token for definitive answers', () => {
    expect(isFinalResetFailure({ kind: 'invalid' })).toBe(true);
    expect(isFinalResetFailure({ kind: 'expired' })).toBe(true);
    expect(isFinalResetFailure({ kind: 'unconfirmed' })).toBe(true);
    expect(isFinalResetFailure({ kind: 'password' })).toBe(false);
    expect(isFinalResetFailure({ kind: 'uncertain' })).toBe(false);
    expect(isFinalResetFailure({ kind: 'rateLimited', retryAfterSeconds: 1 })).toBe(false);
  });
});
