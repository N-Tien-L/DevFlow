// @vitest-environment jsdom
import { StrictMode, act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { BrowserRouter, Route, Routes, useLocation } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AuthProvider } from '../auth/AuthProvider';
import ResetPasswordPage from './ResetPasswordPage';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const TOKEN = 'one-time-reset-token';
const STORAGE_KEY = 'devflow.session.v1';
const user = { id: 'u1', email: 'an@devflow.dev', fullName: 'An', avatarUrl: null };

let root: Root | null = null;
let container: HTMLDivElement;
let loginState: unknown;

function LoginProbe() {
  loginState = useLocation().state;
  return <h1>login</h1>;
}

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

function routeFetch(handlers: Record<string, (init?: RequestInit) => Response>) {
  const fetchMock = vi.fn<typeof fetch>().mockImplementation(async (input, init) => {
    const handler = handlers[String(input)];
    if (!handler) throw new Error(`Unexpected request ${String(input)}`);
    return handler(init);
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

const settle = () => act(() => new Promise<void>((resolve) => setTimeout(resolve, 0)));

async function mount(url: string) {
  window.history.replaceState(null, '', url);
  container = document.createElement('div');
  document.body.appendChild(container);
  root = createRoot(container);
  await act(async () => root?.render(
    <StrictMode>
      <BrowserRouter>
        <AuthProvider>
          <Routes>
            <Route path="/reset-password" element={<ResetPasswordPage />} />
            <Route path="/login" element={<LoginProbe />} />
          </Routes>
        </AuthProvider>
      </BrowserRouter>
    </StrictMode>,
  ));
  await settle();
}

function type(input: HTMLInputElement, value: string) {
  // React tracks the last value it set; going through the native setter makes it see a real change.
  Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set?.call(input, value);
  input.dispatchEvent(new Event('input', { bubbles: true }));
}

async function fillAndSubmit(password: string, confirmation = password) {
  const [newPassword, confirm] = Array.from(container.querySelectorAll<HTMLInputElement>('input'));
  await act(async () => {
    type(newPassword, password);
    type(confirm, confirmation);
  });
  await act(async () => container.querySelector<HTMLButtonElement>('button[type="submit"]')?.click());
  await settle();
}

const heading = () => container.querySelector('h1')?.textContent;
const resetCalls = (fetchMock: ReturnType<typeof routeFetch>) =>
  fetchMock.mock.calls.filter(([input]) => String(input) === '/api/v1/auth/reset-password');

afterEach(async () => {
  await act(async () => root?.unmount());
  root = null;
  container?.remove();
  loginState = undefined;
  sessionStorage.clear();
  localStorage.clear();
  vi.unstubAllGlobals();
  window.history.replaceState(null, '', '/');
});

describe('ResetPasswordPage', () => {
  it('removes the token from the URL at once and keeps it out of storage', async () => {
    routeFetch({});
    await mount(`/reset-password?token=${TOKEN}`);

    expect(window.location.pathname).toBe('/reset-password');
    expect(window.location.href).not.toContain(TOKEN);
    expect(heading()).toBe('Đặt mật khẩu mới');
    expect(JSON.stringify({ ...sessionStorage })).not.toContain(TOKEN);
    expect(JSON.stringify({ ...localStorage })).not.toContain(TOKEN);
  });

  it('does not submit when the client rules fail', async () => {
    const fetchMock = routeFetch({});
    await mount(`/reset-password?token=${TOKEN}`);
    await fillAndSubmit('Secret123', 'Secret124');

    expect(resetCalls(fetchMock)).toHaveLength(0);
    expect(container.textContent).toContain('Mật khẩu nhập lại không khớp.');
    const confirm = container.querySelectorAll<HTMLInputElement>('input')[1];
    expect(confirm.getAttribute('aria-invalid')).toBe('true');
    expect(document.activeElement).toBe(confirm);
  });

  it('posts the in-memory token, signs this tab out and lands on /login with a notice', async () => {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify({ accessToken: 'a', refreshToken: 'r', user }));
    const fetchMock = routeFetch({
      '/api/v1/auth/me': () => json(200, user),
      '/api/v1/auth/reset-password': () => json(200, { message: 'Your password has been reset.' }),
    });
    await mount(`/reset-password?token=${TOKEN}`);
    await fillAndSubmit('NewSecret1');

    const calls = resetCalls(fetchMock);
    expect(calls).toHaveLength(1);
    expect(JSON.parse(String(calls[0][1]?.body))).toEqual({ token: TOKEN, newPassword: 'NewSecret1' });
    expect(window.location.pathname).toBe('/login');
    expect(window.location.href).not.toContain(TOKEN);
    expect(loginState).toEqual({ notice: 'passwordReset' });
    expect(sessionStorage.getItem(STORAGE_KEY)).toBeNull();
  });

  it('shows a dead-link screen for an expired token and does not offer to resubmit it', async () => {
    const fetchMock = routeFetch({
      '/api/v1/auth/reset-password': () => json(410, { status: 410, code: 'RESET_TOKEN_EXPIRED', detail: 'expired' }),
    });
    await mount(`/reset-password?token=${TOKEN}`);
    await fillAndSubmit('NewSecret1');

    expect(resetCalls(fetchMock)).toHaveLength(1);
    expect(heading()).toBe('Liên kết đã hết hạn');
    expect(document.activeElement?.tagName).toBe('H1');
    expect(container.querySelector('form')).toBeNull();
    expect(container.querySelector('a[href="/forgot-password"]')).not.toBeNull();
  });

  it('keeps the form and token after an unanswered attempt so the user can retry', async () => {
    const fetchMock = routeFetch({ '/api/v1/auth/reset-password': () => json(503, { status: 503 }) });
    await mount(`/reset-password?token=${TOKEN}`);
    await fillAndSubmit('NewSecret1');
    await act(async () => container.querySelector<HTMLButtonElement>('button[type="submit"]')?.click());
    await settle();

    const calls = resetCalls(fetchMock);
    expect(calls).toHaveLength(2);
    expect(JSON.parse(String(calls[1][1]?.body))).toMatchObject({ token: TOKEN });
    expect(container.querySelector('[role="alert"]')?.textContent).toContain('Chưa rõ mật khẩu đã được đổi');
  });

  it('explains a missing token instead of showing the form', async () => {
    routeFetch({});
    await mount('/reset-password');
    expect(heading()).toBe('Không tìm thấy liên kết');
    expect(container.querySelector('form')).toBeNull();
  });
});
