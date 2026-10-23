import { ApiError, type ProblemDetail } from '../types/board';

/** Machine-readable `code` values the auth module returns in its RFC 7807 bodies. */
export const AuthErrorCode = {
  AuthenticationRequired: 'AUTHENTICATION_REQUIRED',
  EmailNotVerified: 'EMAIL_NOT_VERIFIED',
  BotChallengeFailed: 'BOT_CHALLENGE_FAILED',
  BotProtectionUnavailable: 'BOT_PROTECTION_UNAVAILABLE',
  DisposableEmail: 'DISPOSABLE_EMAIL',
  EmailDomainUndeliverable: 'EMAIL_DOMAIN_UNDELIVERABLE',
  VerificationTokenInvalid: 'VERIFICATION_TOKEN_INVALID',
  VerificationTokenExpired: 'VERIFICATION_TOKEN_EXPIRED',
  RateLimitExceeded: 'RATE_LIMIT_EXCEEDED',
} as const;

export type AuthErrorCode = (typeof AuthErrorCode)[keyof typeof AuthErrorCode];

const DEFAULT_MESSAGE = 'Yêu cầu không thành công.';
// Upper bound for a server-suggested wait; anything larger is treated as malformed.
const MAX_RETRY_AFTER_SECONDS = 24 * 60 * 60;

function stringField(body: Record<string, unknown>, key: string) {
  const value = body[key];
  return typeof value === 'string' && value.length > 0 ? value : undefined;
}

function fieldErrorsFrom(value: unknown): Record<string, string> | undefined {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return undefined;
  const errors: Record<string, string> = {};
  for (const [field, message] of Object.entries(value)) {
    if (typeof message === 'string' && message.length > 0) errors[field] = message;
  }
  return Object.keys(errors).length > 0 ? errors : undefined;
}

function boundedSeconds(value: number) {
  if (!Number.isFinite(value) || value < 0 || value > MAX_RETRY_AFTER_SECONDS) return undefined;
  return Math.ceil(value);
}

/**
 * Parses an HTTP `Retry-After` header, which is either delta-seconds or an HTTP-date (RFC 9110 §10.2.3).
 * Returns whole seconds to wait, or undefined when the header is absent or malformed.
 */
export function parseRetryAfter(header: string | null | undefined, now = Date.now()): number | undefined {
  const value = header?.trim();
  if (!value) return undefined;
  if (/^\d+$/.test(value)) return boundedSeconds(Number(value));
  // HTTP-dates always name the weekday and month; this keeps Date.parse from reading "-5" as a year.
  if (!/[a-z]{3}/i.test(value)) return undefined;
  const date = Date.parse(value);
  if (Number.isNaN(date)) return undefined;
  return boundedSeconds(Math.max(0, (date - now) / 1000));
}

/**
 * Normalises an RFC 7807 body (or anything the server sent instead) into a {@link ProblemDetail}.
 * The HTTP status wins when the body omits it; `Retry-After` fills `retryAfterSeconds` when the body has none.
 */
export function parseProblemDetail(body: unknown, httpStatus: number, headers?: Headers): ProblemDetail {
  const source = typeof body === 'object' && body !== null && !Array.isArray(body) ? body as Record<string, unknown> : {};
  const bodyRetryAfter = typeof source.retryAfterSeconds === 'number' ? boundedSeconds(source.retryAfterSeconds) : undefined;
  const status = typeof source.status === 'number' && Number.isInteger(source.status) ? source.status : httpStatus;
  let code = stringField(source, 'code');
  // The auth rate limiter answers 429 without a code; give callers one stable value to branch on.
  if (!code && status === 429) code = AuthErrorCode.RateLimitExceeded;
  return {
    type: stringField(source, 'type'),
    title: stringField(source, 'title'),
    status,
    detail: stringField(source, 'detail'),
    instance: stringField(source, 'instance'),
    code,
    correlationId: stringField(source, 'correlationId'),
    retryAfterSeconds: bodyRetryAfter ?? parseRetryAfter(headers?.get('retry-after')),
    errors: fieldErrorsFrom(source.errors),
  };
}

export function problemMessage(problem: ProblemDetail, fallback = DEFAULT_MESSAGE) {
  return problem.detail || problem.title || fallback;
}

/**
 * Reads a fetch response: JSON body on success, {@link ApiError} carrying the parsed ProblemDetail otherwise.
 * `uncertain` marks failures where the server may already have applied a mutation.
 */
export async function readJsonResponse<T>(response: Response, uncertain = false): Promise<T> {
  const contentType = response.headers.get('content-type') ?? '';
  let body: unknown;
  if (response.status !== 204) {
    if (!contentType.includes('json')) {
      if (!response.ok) {
        const problem = parseProblemDetail(undefined, response.status, response.headers);
        throw new ApiError(`Yêu cầu thất bại (${response.status}).`, response.status, problem, uncertain);
      }
      throw new ApiError('Máy chủ trả về dữ liệu không đúng định dạng.', response.status, undefined, uncertain);
    }
    try {
      body = await response.json();
    } catch {
      if (!response.ok) {
        // Empty or broken error body: keep what the status and headers still say (code for 429, Retry-After).
        const problem = parseProblemDetail(undefined, response.status, response.headers);
        throw new ApiError(`Yêu cầu thất bại (${response.status}).`, response.status, problem, uncertain);
      }
      throw new ApiError('Không đọc được phản hồi từ máy chủ.', response.status, undefined, uncertain);
    }
  }
  if (!response.ok) {
    const problem = parseProblemDetail(body, response.status, response.headers);
    throw new ApiError(problemMessage(problem), response.status, problem, uncertain);
  }
  return body as T;
}

export function problemCode(reason: unknown): string | undefined {
  return reason instanceof ApiError ? reason.problem?.code : undefined;
}

export function hasProblemCode(reason: unknown, code: AuthErrorCode | string) {
  return problemCode(reason) === code;
}

export function fieldErrors(reason: unknown): Record<string, string> {
  return reason instanceof ApiError ? reason.problem?.errors ?? {} : {};
}

export function retryAfterSeconds(reason: unknown): number | undefined {
  return reason instanceof ApiError ? reason.problem?.retryAfterSeconds : undefined;
}
