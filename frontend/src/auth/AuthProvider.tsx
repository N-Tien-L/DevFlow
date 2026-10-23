import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import type { PropsWithChildren } from 'react';
import { login, refreshTokens } from '../api/authApi';
import { readJsonResponse } from '../api/problemDetail';
import { ApiError, type UserSummary } from '../types/board';

interface Session {
  accessToken: string;
  refreshToken: string;
  user: UserSummary;
}

/** Why the last session ended: `expired` when the server rejected the refresh token, `signedOut` when the user chose to. */
export type SessionEnd = 'expired' | 'signedOut' | null;

interface AuthContextValue {
  user: UserSummary | null;
  ready: boolean;
  sessionGeneration: number;
  sessionEnd: SessionEnd;
  signIn(email: string, password: string): Promise<void>;
  signOut(): void;
  getValidAccessToken(options?: { minValidityMs?: number; signal?: AbortSignal }): Promise<string>;
  request<T>(path: string, init?: RequestInit): Promise<T>;
}

const STORAGE_KEY = 'devflow.session.v1';
const AuthContext = createContext<AuthContextValue | null>(null);

function readSession(): Session | null {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    const value: unknown = JSON.parse(raw);
    if (
      typeof value !== 'object' || value === null ||
      !('accessToken' in value) || typeof value.accessToken !== 'string' ||
      !('refreshToken' in value) || typeof value.refreshToken !== 'string' ||
      !('user' in value) || typeof value.user !== 'object' || value.user === null
    ) return null;
    return value as Session;
  } catch {
    return null;
  }
}

function accessTokenExpiresAt(token: string): number | null {
  try {
    const payload = token.split('.')[1];
    if (!payload) return null;
    const claims: unknown = JSON.parse(atob(payload.replace(/-/g, '+').replace(/_/g, '/')));
    if (typeof claims !== 'object' || claims === null || !('exp' in claims) || typeof claims.exp !== 'number') return null;
    return claims.exp * 1000;
  } catch {
    return null;
  }
}

export function AuthProvider({ children }: PropsWithChildren) {
  const [session, setSession] = useState<Session | null>(() => readSession());
  const [ready, setReady] = useState(false);
  const sessionRef = useRef(session);
  const refreshPromise = useRef<Promise<Session | null> | null>(null);
  const sessionGenerationRef = useRef(0);
  const [sessionGeneration, setSessionGeneration] = useState(0);
  const [sessionEnd, setSessionEnd] = useState<SessionEnd>(null);

  useEffect(() => {
    sessionRef.current = session;
    if (session) sessionStorage.setItem(STORAGE_KEY, JSON.stringify(session));
    else sessionStorage.removeItem(STORAGE_KEY);
  }, [session]);

  const applySession = useCallback((next: Session | null, replaceSession = false, end: SessionEnd = null) => {
    const previous = sessionRef.current;
    if (previous && !next) setSessionEnd(end);
    else if (next && replaceSession) setSessionEnd(null);
    if (replaceSession || Boolean(previous) !== Boolean(next) || previous?.user.id !== next?.user.id) {
      sessionGenerationRef.current += 1;
      setSessionGeneration(sessionGenerationRef.current);
    }
    sessionRef.current = next;
    setSession(next);
    if (next) sessionStorage.setItem(STORAGE_KEY, JSON.stringify(next));
    else sessionStorage.removeItem(STORAGE_KEY);
  }, []);

  const refreshSession = useCallback((): Promise<Session | null> => {
    const current = sessionRef.current;
    if (!current) return Promise.resolve(null);
    if (refreshPromise.current) return refreshPromise.current;
    const generation = sessionGenerationRef.current;
    const refresh = (async () => {
      try {
        const payload = await refreshTokens(current.refreshToken);
        if (generation !== sessionGenerationRef.current || sessionRef.current?.refreshToken !== current.refreshToken) {
          return sessionRef.current;
        }
        const next = { accessToken: payload.accessToken, refreshToken: payload.refreshToken, user: payload.user };
        applySession(next);
        return next;
      } catch (reason) {
        if (generation !== sessionGenerationRef.current || sessionRef.current?.refreshToken !== current.refreshToken) return sessionRef.current;
        if (reason instanceof ApiError && reason.status >= 400 && reason.status < 500 && reason.status !== 429) {
          applySession(null, false, 'expired');
          return null;
        }
        if (reason instanceof ApiError && reason.status !== 0) throw reason;
        throw new ApiError('Không thể làm mới phiên do lỗi kết nối. Hãy thử lại khi mạng ổn định.', 0);
      } finally {
        if (refreshPromise.current === refresh) refreshPromise.current = null;
      }
    })();
    refreshPromise.current = refresh;
    return refresh;
  }, [applySession]);

  const getValidAccessToken = useCallback(async ({ minValidityMs = 30_000, signal }: { minValidityMs?: number; signal?: AbortSignal } = {}) => {
    if (signal?.aborted) throw new DOMException('Token request was cancelled.', 'AbortError');
    const current = sessionRef.current;
    if (!current) throw new ApiError('Phiên đăng nhập đã hết. Vui lòng đăng nhập lại.', 401);
    const generation = sessionGenerationRef.current;
    const expiresAt = accessTokenExpiresAt(current.accessToken);
    if (expiresAt === null || expiresAt - Date.now() > minValidityMs) return current.accessToken;

    const refreshed = await refreshSession();
    if (signal?.aborted) throw new DOMException('Token request was cancelled.', 'AbortError');
    if (generation !== sessionGenerationRef.current) throw new ApiError('Phiên đăng nhập đã thay đổi.', 401);
    if (!refreshed) throw new ApiError('Phiên đăng nhập đã hết. Vui lòng đăng nhập lại.', 401);
    return refreshed.accessToken;
  }, [refreshSession]);

  useEffect(() => {
    let active = true;
    const verify = async () => {
      const current = sessionRef.current;
      const generation = sessionGenerationRef.current;
      if (!current) {
        if (active) setReady(true);
        return;
      }
      try {
        const response = await fetch('/api/v1/auth/me', { headers: { Authorization: `Bearer ${current.accessToken}` } });
        if (!active || generation !== sessionGenerationRef.current || sessionRef.current?.refreshToken !== current.refreshToken) return;
        if (response.ok) {
          const user = await response.json() as UserSummary;
          applySession({ ...current, user });
        } else if (response.status === 401) {
          await refreshSession();
        } else if (response.status >= 400 && response.status < 500 && response.status !== 429) {
          applySession(null, false, 'expired');
        }
        // 429 and 5xx keep the session: the account is still valid, the server is just unavailable.
      } catch {
        // Keep the existing session during a temporary network outage.
      } finally {
        if (active) setReady(true);
      }
    };
    void verify();
    return () => { active = false; };
  }, [applySession, refreshSession]);

  const signIn = useCallback(async (email: string, password: string) => {
    const payload = await login(email, password);
    applySession({ accessToken: payload.accessToken, refreshToken: payload.refreshToken, user: payload.user }, true);
  }, [applySession]);

  const signOut = useCallback(() => applySession(null, true, 'signedOut'), [applySession]);

  const request = useCallback(async <T,>(path: string, init: RequestInit = {}): Promise<T> => {
    let current = sessionRef.current;
    if (!current) throw new ApiError('Phiên đăng nhập đã hết. Vui lòng đăng nhập lại.', 401);
    const generation = sessionGenerationRef.current;
    const method = (init.method ?? 'GET').toUpperCase();
    const send = (accessToken: string) => {
      const headers = new Headers(init.headers);
      headers.set('Authorization', `Bearer ${accessToken}`);
      if (init.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
      const controller = new AbortController();
      const timeout = window.setTimeout(() => controller.abort(), 10_000);
      const onCallerAbort = () => controller.abort(init.signal?.reason);
      init.signal?.addEventListener('abort', onCallerAbort, { once: true });
      if (init.signal?.aborted) onCallerAbort();
      return fetch(path, { ...init, headers, signal: controller.signal }).finally(() => {
        window.clearTimeout(timeout);
        init.signal?.removeEventListener('abort', onCallerAbort);
      });
    };
    let response: Response;
    try {
      response = await send(current.accessToken);
    } catch {
      throw new ApiError('Không kết nối được máy chủ. Trạng thái lưu có thể chưa xác định.', 0, undefined, method !== 'GET');
    }
    if (response.status === 401) {
      if (generation !== sessionGenerationRef.current) {
        throw new ApiError('Phiên đăng nhập đã thay đổi. Hãy tải lại dữ liệu trước khi tiếp tục.', 401, undefined, method !== 'GET');
      }
      if (method !== 'GET') {
        await refreshSession();
        throw new ApiError('Phiên đăng nhập hết hạn. Hãy kiểm tra board rồi thử lại thao tác.', 401, undefined, true);
      }
      current = await refreshSession();
      if (!current || generation !== sessionGenerationRef.current) throw new ApiError('Phiên đăng nhập đã hết. Vui lòng đăng nhập lại.', 401);
      try {
        response = await send(current.accessToken);
      } catch {
        throw new ApiError('Không kết nối được máy chủ.', 0);
      }
    }
    return readJsonResponse<T>(response, method !== 'GET' && (response.ok || response.status >= 500));
  }, [refreshSession]);

  const value = useMemo(() => ({ user: session?.user ?? null, ready, sessionGeneration, sessionEnd, signIn, signOut, getValidAccessToken, request }), [session, ready, sessionGeneration, sessionEnd, signIn, signOut, getValidAccessToken, request]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) throw new Error('useAuth must be used within AuthProvider');
  return context;
}
