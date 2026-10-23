import { readJsonResponse } from './problemDetail';
import { ApiError, type AuthResponse } from '../types/board';
import type { MessageResponse, RegisterRequest, RegisterResponse } from '../types/auth';

// Unauthenticated auth endpoints. They never carry a Bearer token; authenticated calls go through AuthProvider.request.

const REQUEST_TIMEOUT_MS = 10_000;

function normaliseEmail(email: string) {
  return email.trim().toLowerCase();
}

async function postPublic<T>(path: string, body: unknown, signal?: AbortSignal): Promise<T> {
  if (signal?.aborted) throw new DOMException('Request was cancelled.', 'AbortError');
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
  const onCallerAbort = () => controller.abort(signal?.reason);
  signal?.addEventListener('abort', onCallerAbort, { once: true });
  let response: Response;
  try {
    response = await fetch(path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json, application/problem+json' },
      body: JSON.stringify(body),
      credentials: 'same-origin',
      signal: controller.signal,
    });
  } catch (reason) {
    if (signal?.aborted) throw reason;
    throw new ApiError('Không kết nối được máy chủ. Vui lòng kiểm tra mạng rồi thử lại.', 0);
  } finally {
    clearTimeout(timeout);
    signal?.removeEventListener('abort', onCallerAbort);
  }
  return readJsonResponse<T>(response);
}

export function register(request: RegisterRequest, signal?: AbortSignal) {
  return postPublic<RegisterResponse>('/api/v1/auth/register', {
    email: normaliseEmail(request.email),
    password: request.password,
    fullName: request.fullName.trim(),
    turnstileToken: request.turnstileToken || null,
  }, signal);
}

export function verifyEmail(token: string, signal?: AbortSignal) {
  return postPublic<MessageResponse>('/api/v1/auth/verify-email', { token: token.trim() }, signal);
}

export function resendVerification(email: string, signal?: AbortSignal) {
  return postPublic<MessageResponse>('/api/v1/auth/resend-verification', { email: normaliseEmail(email) }, signal);
}

export function login(email: string, password: string, signal?: AbortSignal) {
  return postPublic<AuthResponse>('/api/v1/auth/login', { email: normaliseEmail(email), password }, signal);
}

export function refreshTokens(refreshToken: string, signal?: AbortSignal) {
  return postPublic<AuthResponse>('/api/v1/auth/refresh', { refreshToken }, signal);
}
