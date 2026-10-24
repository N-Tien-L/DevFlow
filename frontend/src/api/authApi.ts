import { readJsonResponse } from './problemDetail';
import { ApiError, type AuthResponse } from '../types/board';
import type { MessageResponse, RegisterRequest, RegisterResponse } from '../types/auth';

// Unauthenticated auth endpoints. They never carry a Bearer token; authenticated calls go through AuthProvider.request.

const REQUEST_TIMEOUT_MS = 10_000;

function normaliseEmail(email: string) {
  return email.trim().toLowerCase();
}

function cancelled(signal: AbortSignal) {
  return signal.reason ?? new DOMException('Request was cancelled.', 'AbortError');
}

async function postPublic<T>(path: string, body: unknown, signal?: AbortSignal): Promise<T> {
  if (signal?.aborted) throw cancelled(signal);
  const controller = new AbortController();
  let timedOut = false;
  const timeout = setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, REQUEST_TIMEOUT_MS);
  const onCallerAbort = () => controller.abort(signal?.reason);
  signal?.addEventListener('abort', onCallerAbort, { once: true });
  // The timer and caller signal stay armed until the body is read: a server that sends headers and then
  // stalls must still time out, or login/refresh (and the single-flight refresh promise) would hang.
  try {
    const response = await fetch(path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json, application/problem+json' },
      body: JSON.stringify(body),
      credentials: 'same-origin',
      signal: controller.signal,
    });
    return await readJsonResponse<T>(response);
  } catch (reason) {
    if (timedOut) throw new ApiError('Máy chủ phản hồi quá lâu. Vui lòng thử lại.', 0);
    if (signal?.aborted) throw cancelled(signal);
    if (reason instanceof ApiError) throw reason;
    throw new ApiError('Không kết nối được máy chủ. Vui lòng kiểm tra mạng rồi thử lại.', 0);
  } finally {
    clearTimeout(timeout);
    signal?.removeEventListener('abort', onCallerAbort);
  }
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

export function forgotPassword(email: string, turnstileToken: string | null, signal?: AbortSignal) {
  return postPublic<MessageResponse>('/api/v1/auth/forgot-password', {
    email: normaliseEmail(email),
    turnstileToken: turnstileToken || null,
  }, signal);
}

/** The password is sent as typed: the server neither trims nor normalises it. */
export function resetPassword(token: string, newPassword: string, signal?: AbortSignal) {
  return postPublic<MessageResponse>('/api/v1/auth/reset-password', { token, newPassword }, signal);
}

export function login(email: string, password: string, signal?: AbortSignal) {
  return postPublic<AuthResponse>('/api/v1/auth/login', { email: normaliseEmail(email), password }, signal);
}

export function refreshTokens(refreshToken: string, signal?: AbortSignal) {
  return postPublic<AuthResponse>('/api/v1/auth/refresh', { refreshToken }, signal);
}
