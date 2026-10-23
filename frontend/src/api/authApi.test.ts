import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../types/board';
import { login, register, resendVerification, verifyEmail } from './authApi';
import { AuthErrorCode, problemCode } from './problemDetail';

function jsonResponse(status: number, body: unknown, headers: Record<string, string> = {}) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...headers } });
}

function mockFetch(response: Response) {
  const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(response);
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

function sentRequest(fetchMock: ReturnType<typeof mockFetch>) {
  const [path, init] = fetchMock.mock.calls[0];
  return { path, init: init as RequestInit, body: JSON.parse(String(init?.body)) as Record<string, unknown> };
}

/** Headers arrive at once, the body never does; like real fetch, aborting the request signal errors the body. */
function stalledBodyFetch() {
  return vi.fn<typeof fetch>().mockImplementation(async (_input, init) => {
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        init?.signal?.addEventListener('abort', () => controller.error(new DOMException('aborted', 'AbortError')), { once: true });
      },
    });
    return new Response(stream, { status: 200, headers: { 'Content-Type': 'application/json' } });
  });
}

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe('public auth API', () => {
  it('posts a normalised registration without an Authorization header', async () => {
    const fetchMock = mockFetch(jsonResponse(201, { email: 'a@b.dev', status: 'PENDING_VERIFICATION', message: 'ok' }));
    await register({ email: '  A@B.dev ', password: 'Secret123', fullName: '  An Lê ', turnstileToken: 'tt' });
    const { path, init, body } = sentRequest(fetchMock);
    expect(path).toBe('/api/v1/auth/register');
    expect(init.method).toBe('POST');
    expect(new Headers(init.headers).has('Authorization')).toBe(false);
    expect(body).toEqual({ email: 'a@b.dev', password: 'Secret123', fullName: 'An Lê', turnstileToken: 'tt' });
  });

  it('sends verify-email, resend-verification and login bodies', async () => {
    let fetchMock = mockFetch(jsonResponse(200, { message: 'verified' }));
    await expect(verifyEmail(' tok ')).resolves.toEqual({ message: 'verified' });
    expect(sentRequest(fetchMock)).toMatchObject({ path: '/api/v1/auth/verify-email', body: { token: 'tok' } });

    fetchMock = mockFetch(jsonResponse(202, { message: 'sent' }));
    await resendVerification('X@Y.dev');
    expect(sentRequest(fetchMock)).toMatchObject({ path: '/api/v1/auth/resend-verification', body: { email: 'x@y.dev' } });

    fetchMock = mockFetch(jsonResponse(200, { accessToken: 'a', refreshToken: 'r', tokenType: 'Bearer', expiresIn: 900, user: {} }));
    await login('X@Y.dev', 'pw');
    expect(sentRequest(fetchMock)).toMatchObject({ path: '/api/v1/auth/login', body: { email: 'x@y.dev', password: 'pw' } });
  });

  it('surfaces EMAIL_NOT_VERIFIED from login as an ApiError', async () => {
    mockFetch(jsonResponse(403, { status: 403, detail: 'Email address has not been verified', code: 'EMAIL_NOT_VERIFIED' }));
    const reason = await login('a@b.dev', 'pw').catch((error: unknown) => error);
    expect(reason).toBeInstanceOf(ApiError);
    expect((reason as ApiError).status).toBe(403);
    expect(problemCode(reason)).toBe(AuthErrorCode.EmailNotVerified);
  });

  it('maps a network failure to status 0', async () => {
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockRejectedValue(new TypeError('Failed to fetch')));
    const reason = await resendVerification('a@b.dev').catch((error: unknown) => error);
    expect((reason as ApiError).status).toBe(0);
  });

  it('times out when the body stalls after the headers', async () => {
    vi.useFakeTimers();
    vi.stubGlobal('fetch', stalledBodyFetch());
    const outcome = login('a@b.dev', 'pw').catch((error: unknown) => error);
    await vi.advanceTimersByTimeAsync(9_999);
    let settled = false;
    void outcome.then(() => { settled = true; });
    await Promise.resolve();
    expect(settled).toBe(false);
    await vi.advanceTimersByTimeAsync(1);
    const reason = await outcome;
    expect(reason).toBeInstanceOf(ApiError);
    expect((reason as ApiError).status).toBe(0);
  });

  it('honours a caller abort that arrives after the headers', async () => {
    vi.stubGlobal('fetch', stalledBodyFetch());
    const controller = new AbortController();
    const outcome = verifyEmail('tok', controller.signal).catch((error: unknown) => error);
    await new Promise((resolve) => setTimeout(resolve, 0));
    controller.abort();
    expect(await outcome).toMatchObject({ name: 'AbortError' });
  });

  it('keeps the rate-limit code and Retry-After when the 429 body is empty', async () => {
    mockFetch(new Response('', { status: 429, headers: { 'Content-Type': 'application/problem+json', 'Retry-After': '60' } }));
    const reason = await resendVerification('a@b.dev').catch((error: unknown) => error);
    expect((reason as ApiError).status).toBe(429);
    expect(problemCode(reason)).toBe(AuthErrorCode.RateLimitExceeded);
    expect((reason as ApiError).problem?.retryAfterSeconds).toBe(60);
  });

  it('rethrows a caller abort instead of reporting a network error', async () => {
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockRejectedValue(new DOMException('aborted', 'AbortError')));
    const controller = new AbortController();
    controller.abort();
    await expect(verifyEmail('tok', controller.signal)).rejects.toMatchObject({ name: 'AbortError' });
  });
});
