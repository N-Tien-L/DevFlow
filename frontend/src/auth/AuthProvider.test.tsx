// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthProvider, useAuth } from './AuthProvider';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

type Auth = ReturnType<typeof useAuth>;

const STORAGE_KEY = 'devflow.session.v1';
const user = { id: 'u1', email: 'an@devflow.dev', fullName: 'An', avatarUrl: null };
const b64 = (value: object) => btoa(JSON.stringify(value)).replace(/=+$/, '');
// Expires in 10s, inside getValidAccessToken's default 30s window, so every call wants a refresh.
const expiringToken = () => `${b64({ alg: 'HS256' })}.${b64({ sub: 'u1', exp: Math.floor(Date.now() / 1000) + 10 })}.sig`;

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => { resolve = done; });
  return { promise, resolve };
}

let root: Root | null = null;
let auth: Auth;

function Probe() {
  auth = useAuth();
  return null;
}

const settle = () => act(() => new Promise<void>((resolve) => setTimeout(resolve, 0)));

async function mount() {
  root = createRoot(document.createElement('div'));
  await act(async () => root?.render(<AuthProvider><Probe /></AuthProvider>));
  await settle();
}

function routeFetch(handlers: Record<string, (init?: RequestInit) => Response | Promise<Response>>) {
  const fetchMock = vi.fn<typeof fetch>().mockImplementation(async (input, init) => {
    const handler = handlers[String(input)];
    if (!handler) throw new Error(`Unexpected request ${String(input)}`);
    return handler(init);
  });
  vi.stubGlobal('fetch', fetchMock);
  return (path: string) => fetchMock.mock.calls.filter(([input]) => String(input) === path).length;
}

/** Headers arrive at once, the body never does; like real fetch, aborting the request signal errors the body. */
function stalledBody(init?: RequestInit) {
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      init?.signal?.addEventListener('abort', () => controller.error(new DOMException('aborted', 'AbortError')), { once: true });
    },
  });
  return new Response(stream, { status: 200, headers: { 'Content-Type': 'application/json' } });
}

beforeEach(() => {
  sessionStorage.setItem(STORAGE_KEY, JSON.stringify({ accessToken: expiringToken(), refreshToken: 'refresh-1', user }));
});

afterEach(async () => {
  vi.useRealTimers();
  await act(async () => root?.unmount());
  root = null;
  sessionStorage.clear();
  vi.unstubAllGlobals();
});

describe('AuthProvider', () => {
  it('shares one refresh request between concurrent callers', async () => {
    const refresh = deferred<Response>();
    const calls = routeFetch({
      '/api/v1/auth/me': () => json(200, user),
      '/api/v1/auth/refresh': () => refresh.promise,
    });
    await mount();

    let tokens!: Promise<string[]>;
    await act(async () => {
      tokens = Promise.all([auth.getValidAccessToken(), auth.getValidAccessToken(), auth.getValidAccessToken()]);
    });
    expect(calls('/api/v1/auth/refresh')).toBe(1);

    await act(async () => refresh.resolve(json(200, { accessToken: 'access-2', refreshToken: 'refresh-2', tokenType: 'Bearer', expiresIn: 900, user })));
    expect(await tokens).toEqual(['access-2', 'access-2', 'access-2']);
    expect(JSON.parse(sessionStorage.getItem(STORAGE_KEY) ?? '{}').refreshToken).toBe('refresh-2');
  });

  it('does not resurrect the session when sign-out happens during a refresh', async () => {
    const refresh = deferred<Response>();
    routeFetch({
      '/api/v1/auth/me': () => json(200, user),
      '/api/v1/auth/refresh': () => refresh.promise,
    });
    await mount();

    let outcome!: Promise<unknown>;
    await act(async () => {
      outcome = auth.getValidAccessToken().catch((error: unknown) => error);
    });
    await act(async () => auth.signOut());
    await act(async () => refresh.resolve(json(200, { accessToken: 'access-2', refreshToken: 'refresh-2', tokenType: 'Bearer', expiresIn: 900, user })));

    expect(await outcome).toMatchObject({ status: 401 });
    expect(auth.user).toBeNull();
    expect(auth.sessionEnd).toBe('signedOut');
    expect(sessionStorage.getItem(STORAGE_KEY)).toBeNull();
  });

  it.each([429, 503])('keeps the session when /me answers %i', async (status) => {
    routeFetch({ '/api/v1/auth/me': () => json(status, { status }) });
    await mount();

    expect(auth.ready).toBe(true);
    expect(auth.user).toEqual(user);
    expect(sessionStorage.getItem(STORAGE_KEY)).not.toBeNull();
  });

  it('ends the session as expired when the refresh token is rejected', async () => {
    routeFetch({
      '/api/v1/auth/me': () => json(401, { status: 401, code: 'AUTHENTICATION_REQUIRED' }),
      '/api/v1/auth/refresh': () => json(401, { status: 401, detail: 'Refresh token expired' }),
    });
    await mount();

    expect(auth.user).toBeNull();
    expect(auth.sessionEnd).toBe('expired');
    expect(sessionStorage.getItem(STORAGE_KEY)).toBeNull();
  });

  describe('request', () => {
    it('times out when the body stalls after the headers', async () => {
      routeFetch({ '/api/v1/auth/me': () => json(200, user), '/api/v1/boards/b1': stalledBody });
      await mount();
      vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });

      const outcome = auth.request('/api/v1/boards/b1').catch((error: unknown) => error);
      await vi.advanceTimersByTimeAsync(9_999);
      let settled = false;
      void outcome.then(() => { settled = true; });
      await Promise.resolve();
      expect(settled).toBe(false);

      await vi.advanceTimersByTimeAsync(1);
      expect(await outcome).toMatchObject({ status: 0, uncertain: false });
    });

    it('marks a stalled mutation as uncertain', async () => {
      routeFetch({ '/api/v1/auth/me': () => json(200, user), '/api/v1/tasks/t1/move': stalledBody });
      await mount();
      vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });

      const outcome = auth.request('/api/v1/tasks/t1/move', { method: 'PATCH', body: '{}' }).catch((error: unknown) => error);
      await vi.advanceTimersByTimeAsync(10_000);
      expect(await outcome).toMatchObject({ status: 0, uncertain: true });
    });

    it('honours a caller abort that arrives after the headers', async () => {
      routeFetch({ '/api/v1/auth/me': () => json(200, user), '/api/v1/boards/b1': stalledBody });
      await mount();

      const controller = new AbortController();
      const outcome = auth.request('/api/v1/boards/b1', { signal: controller.signal }).catch((error: unknown) => error);
      await new Promise((resolve) => setTimeout(resolve, 0));
      controller.abort();
      expect(await outcome).toMatchObject({ status: 0 });
    });

    it('refreshes once on 401 and retries a GET with the new token', async () => {
      const seen: string[] = [];
      routeFetch({
        '/api/v1/auth/me': () => json(200, user),
        '/api/v1/auth/refresh': () => json(200, { accessToken: 'access-2', refreshToken: 'refresh-2', tokenType: 'Bearer', expiresIn: 900, user }),
        '/api/v1/boards/b1': (init) => {
          const token = new Headers(init?.headers).get('Authorization') ?? '';
          seen.push(token);
          return token === 'Bearer access-2' ? json(200, { id: 'b1' }) : json(401, { status: 401, code: 'AUTHENTICATION_REQUIRED' });
        },
      });
      await mount();

      let result: unknown;
      await act(async () => { result = await auth.request('/api/v1/boards/b1'); });
      expect(result).toEqual({ id: 'b1' });
      expect(seen).toHaveLength(2);
      expect(seen[1]).toBe('Bearer access-2');
    });
  });
});
