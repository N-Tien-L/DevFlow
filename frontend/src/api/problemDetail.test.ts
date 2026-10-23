import { describe, expect, it } from 'vitest';
import { ApiError } from '../types/board';
import {
  AuthErrorCode,
  fieldErrors,
  hasProblemCode,
  parseProblemDetail,
  parseRetryAfter,
  problemCode,
  readJsonResponse,
  retryAfterSeconds,
} from './problemDetail';

function problemResponse(status: number, body: unknown, headers: Record<string, string> = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/problem+json', ...headers },
  });
}

async function failureOf(response: Response) {
  try {
    await readJsonResponse(response);
  } catch (reason) {
    return reason;
  }
  throw new Error('Expected the response to be rejected');
}

describe('parseRetryAfter', () => {
  it('reads delta-seconds', () => {
    expect(parseRetryAfter('30')).toBe(30);
    expect(parseRetryAfter(' 0 ')).toBe(0);
  });

  it('reads an HTTP-date relative to now', () => {
    const now = Date.parse('2026-10-23T10:00:00Z');
    expect(parseRetryAfter('Fri, 23 Oct 2026 10:00:45 GMT', now)).toBe(45);
    expect(parseRetryAfter('Fri, 23 Oct 2026 09:59:00 GMT', now)).toBe(0);
  });

  it('ignores missing, malformed, negative and absurd values', () => {
    expect(parseRetryAfter(null)).toBeUndefined();
    expect(parseRetryAfter('')).toBeUndefined();
    expect(parseRetryAfter('soon')).toBeUndefined();
    expect(parseRetryAfter('-5')).toBeUndefined();
    expect(parseRetryAfter('999999999')).toBeUndefined();
  });
});

describe('parseProblemDetail', () => {
  it('keeps the standard RFC 7807 members and extensions', () => {
    const problem = parseProblemDetail({
      type: 'about:blank',
      title: 'Forbidden',
      status: 403,
      detail: 'Email address has not been verified',
      instance: '/api/v1/auth/login',
      code: 'EMAIL_NOT_VERIFIED',
      correlationId: 'c-1',
      timestamp: '2026-10-23T10:00:00Z',
    }, 403);
    expect(problem).toMatchObject({
      type: 'about:blank',
      title: 'Forbidden',
      status: 403,
      detail: 'Email address has not been verified',
      instance: '/api/v1/auth/login',
      code: AuthErrorCode.EmailNotVerified,
      correlationId: 'c-1',
    });
  });

  it('falls back to the HTTP status and tolerates non-object bodies', () => {
    expect(parseProblemDetail(undefined, 502)).toMatchObject({ status: 502, code: undefined });
    expect(parseProblemDetail('oops', 500).status).toBe(500);
    expect(parseProblemDetail([1, 2], 400).errors).toBeUndefined();
  });

  it('keeps only string field errors', () => {
    const problem = parseProblemDetail({
      status: 400,
      errors: { email: 'Invalid email format', password: 'Password is required', age: 3, nested: { a: 'b' } },
    }, 400);
    expect(problem.errors).toEqual({ email: 'Invalid email format', password: 'Password is required' });
  });

  it('prefers retryAfterSeconds from the body, else the Retry-After header', () => {
    expect(parseProblemDetail({ status: 429, retryAfterSeconds: 12 }, 429, new Headers({ 'Retry-After': '60' })).retryAfterSeconds).toBe(12);
    expect(parseProblemDetail({ status: 503 }, 503, new Headers({ 'Retry-After': '30' })).retryAfterSeconds).toBe(30);
  });

  it('assigns RATE_LIMIT_EXCEEDED to a 429 without a code', () => {
    expect(parseProblemDetail({ status: 429, retryAfterSeconds: 5 }, 429).code).toBe(AuthErrorCode.RateLimitExceeded);
  });
});

describe('readJsonResponse', () => {
  it('returns the JSON body on success and undefined on 204', async () => {
    const ok = new Response(JSON.stringify({ message: 'ok' }), { status: 200, headers: { 'Content-Type': 'application/json' } });
    await expect(readJsonResponse(ok)).resolves.toEqual({ message: 'ok' });
    await expect(readJsonResponse(new Response(null, { status: 204 }))).resolves.toBeUndefined();
  });

  it.each([
    [403, 'EMAIL_NOT_VERIFIED'],
    [400, 'BOT_CHALLENGE_FAILED'],
    [400, 'DISPOSABLE_EMAIL'],
    [400, 'EMAIL_DOMAIN_UNDELIVERABLE'],
    [400, 'VERIFICATION_TOKEN_INVALID'],
    [410, 'VERIFICATION_TOKEN_EXPIRED'],
    [503, 'BOT_PROTECTION_UNAVAILABLE'],
    [401, 'AUTHENTICATION_REQUIRED'],
  ])('exposes status %i and code %s as an ApiError', async (status, code) => {
    const reason = await failureOf(problemResponse(status, { status, title: 'Error', detail: `detail for ${code}`, code }));
    expect(reason).toBeInstanceOf(ApiError);
    const error = reason as ApiError;
    expect(error.status).toBe(status);
    expect(error.message).toBe(`detail for ${code}`);
    expect(problemCode(error)).toBe(code);
    expect(hasProblemCode(error, code)).toBe(true);
  });

  it('carries field errors for a disposable-email rejection', async () => {
    const reason = await failureOf(problemResponse(400, {
      status: 400,
      title: 'Validation Failed',
      detail: 'Validation failed for one or more fields',
      code: 'DISPOSABLE_EMAIL',
      errors: { email: 'Disposable email addresses are not allowed' },
    }));
    expect(fieldErrors(reason)).toEqual({ email: 'Disposable email addresses are not allowed' });
  });

  it('carries Retry-After for a rate-limited login', async () => {
    const reason = await failureOf(problemResponse(429, { status: 429, title: 'Too Many Requests', retryAfterSeconds: 42 }, { 'Retry-After': '42' }));
    expect(problemCode(reason)).toBe(AuthErrorCode.RateLimitExceeded);
    expect(retryAfterSeconds(reason)).toBe(42);
  });

  it('reads Retry-After even when the error body is not JSON', async () => {
    const reason = await failureOf(new Response('busy', { status: 503, headers: { 'Content-Type': 'text/plain', 'Retry-After': '30' } }));
    expect((reason as ApiError).status).toBe(503);
    expect(retryAfterSeconds(reason)).toBe(30);
  });

  it('marks uncertain failures when asked', async () => {
    const reason = await readJsonResponse(problemResponse(500, { status: 500 }), true).catch((error: unknown) => error);
    expect((reason as ApiError).uncertain).toBe(true);
  });

  it('returns safe defaults for non-ApiError reasons', () => {
    expect(problemCode(new Error('x'))).toBeUndefined();
    expect(fieldErrors('x')).toEqual({});
    expect(retryAfterSeconds(undefined)).toBeUndefined();
  });
});
