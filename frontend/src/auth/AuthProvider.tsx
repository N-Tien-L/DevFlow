import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import type { PropsWithChildren } from 'react';
import { ApiError, type AuthResponse, type UserSummary } from '../types/board';

interface Session {
  accessToken: string;
  refreshToken: string;
  user: UserSummary;
}

interface AuthContextValue {
  user: UserSummary | null;
  ready: boolean;
  signIn(email: string, password: string): Promise<void>;
  signOut(): void;
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

function problemFrom(value: unknown): { message: string; problem?: ApiError['problem'] } {
  if (typeof value !== 'object' || value === null) return { message: 'Yêu cầu không thành công.' };
  const body = value as Record<string, unknown>;
  const problem = {
    code: typeof body.code === 'string' ? body.code : undefined,
    correlationId: typeof body.correlationId === 'string' ? body.correlationId : undefined,
    retryAfterSeconds: typeof body.retryAfterSeconds === 'number' ? body.retryAfterSeconds : undefined,
    status: typeof body.status === 'number' ? body.status : undefined,
    title: typeof body.title === 'string' ? body.title : undefined,
    detail: typeof body.detail === 'string' ? body.detail : undefined,
  };
  return { message: problem.detail || problem.title || 'Yêu cầu không thành công.', problem };
}

async function parseResponse<T>(response: Response, uncertain: boolean): Promise<T> {
  const contentType = response.headers.get('content-type') ?? '';
  let body: unknown;
  if (response.status !== 204) {
    if (!contentType.includes('application/json')) {
      if (!response.ok) throw new ApiError(`Yêu cầu thất bại (${response.status}).`, response.status, undefined, uncertain);
      throw new ApiError('Máy chủ trả về dữ liệu không đúng định dạng.', response.status, undefined, uncertain);
    }
    try {
      body = await response.json();
    } catch {
      throw new ApiError('Không đọc được phản hồi từ máy chủ.', response.status, undefined, uncertain);
    }
  }
  if (!response.ok) {
    const parsed = problemFrom(body);
    throw new ApiError(parsed.message, response.status, parsed.problem, uncertain);
  }
  return body as T;
}

export function AuthProvider({ children }: PropsWithChildren) {
  const [session, setSession] = useState<Session | null>(() => readSession());
  const [ready, setReady] = useState(false);
  const sessionRef = useRef(session);
  const refreshPromise = useRef<Promise<Session | null> | null>(null);

  useEffect(() => {
    sessionRef.current = session;
    if (session) sessionStorage.setItem(STORAGE_KEY, JSON.stringify(session));
    else sessionStorage.removeItem(STORAGE_KEY);
  }, [session]);

  const applySession = useCallback((next: Session | null) => {
    sessionRef.current = next;
    setSession(next);
    if (next) sessionStorage.setItem(STORAGE_KEY, JSON.stringify(next));
    else sessionStorage.removeItem(STORAGE_KEY);
  }, []);

  const refreshSession = useCallback((): Promise<Session | null> => {
    const current = sessionRef.current;
    if (!current) return Promise.resolve(null);
    if (refreshPromise.current) return refreshPromise.current;
    refreshPromise.current = (async () => {
      try {
        const response = await fetch('/api/v1/auth/refresh', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ refreshToken: current.refreshToken }),
        });
        const payload = await parseResponse<AuthResponse>(response, false);
        const next = { accessToken: payload.accessToken, refreshToken: payload.refreshToken, user: payload.user };
        applySession(next);
        return next;
      } catch {
        applySession(null);
        return null;
      } finally {
        refreshPromise.current = null;
      }
    })();
    return refreshPromise.current;
  }, [applySession]);

  useEffect(() => {
    let active = true;
    const verify = async () => {
      const current = sessionRef.current;
      if (!current) {
        if (active) setReady(true);
        return;
      }
      try {
        const response = await fetch('/api/v1/auth/me', { headers: { Authorization: `Bearer ${current.accessToken}` } });
        if (response.ok) {
          const user = await response.json() as UserSummary;
          applySession({ ...current, user });
        } else if (response.status === 401) {
          await refreshSession();
        } else {
          applySession(null);
        }
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
    const response = await fetch('/api/v1/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email: email.trim().toLowerCase(), password }),
    });
    const payload = await parseResponse<AuthResponse>(response, false);
    applySession({ accessToken: payload.accessToken, refreshToken: payload.refreshToken, user: payload.user });
  }, [applySession]);

  const signOut = useCallback(() => applySession(null), [applySession]);

  const request = useCallback(async <T,>(path: string, init: RequestInit = {}): Promise<T> => {
    let current = sessionRef.current;
    if (!current) throw new ApiError('Phiên đăng nhập đã hết. Vui lòng đăng nhập lại.', 401);
    const method = (init.method ?? 'GET').toUpperCase();
    const send = (accessToken: string) => {
      const headers = new Headers(init.headers);
      headers.set('Authorization', `Bearer ${accessToken}`);
      if (init.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
      const controller = new AbortController();
      const timeout = window.setTimeout(() => controller.abort(), 10_000);
      return fetch(path, { ...init, headers, signal: init.signal ?? controller.signal }).finally(() => window.clearTimeout(timeout));
    };
    let response: Response;
    try {
      response = await send(current.accessToken);
    } catch {
      throw new ApiError('Không kết nối được máy chủ. Trạng thái lưu có thể chưa xác định.', 0, undefined, method !== 'GET');
    }
    if (response.status === 401) {
      if (method !== 'GET') {
        await refreshSession();
        throw new ApiError('Phiên đăng nhập hết hạn. Hãy kiểm tra board rồi thử lại thao tác.', 401, undefined, true);
      }
      current = await refreshSession();
      if (!current) throw new ApiError('Phiên đăng nhập đã hết. Vui lòng đăng nhập lại.', 401);
      try {
        response = await send(current.accessToken);
      } catch {
        throw new ApiError('Không kết nối được máy chủ.', 0);
      }
    }
    return parseResponse<T>(response, method !== 'GET' && (response.ok || response.status >= 500));
  }, [refreshSession]);

  const value = useMemo(() => ({ user: session?.user ?? null, ready, signIn, signOut, request }), [session, ready, signIn, signOut, request]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) throw new Error('useAuth must be used within AuthProvider');
  return context;
}
