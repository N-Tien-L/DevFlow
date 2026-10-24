import { AuthErrorCode, fieldErrors, problemCode, retryAfterSeconds } from '../api/problemDetail';
import { ApiError } from '../types/board';

// Default waits when a 429/503 arrives without Retry-After.
const DEFAULT_RATE_LIMIT_WAIT_SECONDS = 60;
const DEFAULT_BOT_SERVICE_WAIT_SECONDS = 30;

export type ForgotPasswordFailure =
  /** The server rejected the address itself (field error on `email`). */
  | { kind: 'email' }
  /** Turnstile token missing, expired or rejected: the user has to solve the challenge again. */
  | { kind: 'botChallenge' }
  /** Per-IP or per-address limit (429), or Turnstile unreachable (503): wait, then retry. */
  | { kind: 'wait'; retryAfterSeconds: number }
  | { kind: 'failed' };

/**
 * Maps a forgot-password failure to what the form shows. Success is always the same neutral message,
 * and none of these outcomes depends on whether the address has an account.
 */
export function forgotPasswordFailure(reason: unknown): ForgotPasswordFailure {
  const code = problemCode(reason);
  if (code === AuthErrorCode.BotChallengeFailed) return { kind: 'botChallenge' };
  if (code === AuthErrorCode.RateLimitExceeded) {
    return { kind: 'wait', retryAfterSeconds: retryAfterSeconds(reason) ?? DEFAULT_RATE_LIMIT_WAIT_SECONDS };
  }
  if (code === AuthErrorCode.BotProtectionUnavailable) {
    return { kind: 'wait', retryAfterSeconds: retryAfterSeconds(reason) ?? DEFAULT_BOT_SERVICE_WAIT_SECONDS };
  }
  const errors = fieldErrors(reason);
  if (errors.email) return { kind: 'email' };
  // A rejected turnstileToken field (e.g. oversized) is fixed the same way as a failed challenge.
  if (errors.turnstileToken) return { kind: 'botChallenge' };
  return { kind: 'failed' };
}

export type ResetPasswordFailure =
  /** Token unknown, already used, superseded by a newer link, or malformed. */
  | { kind: 'invalid' }
  | { kind: 'expired' }
  /** The server rejected the new password (rules the client check may have missed). */
  | { kind: 'password' }
  | { kind: 'rateLimited'; retryAfterSeconds: number }
  /** No definite answer (network, timeout, 5xx): the password may already have been changed. */
  | { kind: 'uncertain' }
  /**
   * A 2xx arrived but its body could not be read. Retrying would most likely meet a spent token, so the page
   * stops here and points to signing in with the new password or requesting a new link.
   */
  | { kind: 'unconfirmed' }
  | { kind: 'failed' };

/** Maps a reset-password failure to what the page shows. Status is the fallback when the body has no code. */
export function resetPasswordFailure(reason: unknown): ResetPasswordFailure {
  const code = problemCode(reason);
  const status = reason instanceof ApiError ? reason.status : -1;
  if (status >= 200 && status < 300) return { kind: 'unconfirmed' };
  if (code === AuthErrorCode.ResetTokenExpired || (!code && status === 410)) return { kind: 'expired' };
  if (code === AuthErrorCode.ResetTokenInvalid) return { kind: 'invalid' };
  if (code === AuthErrorCode.RateLimitExceeded) {
    return { kind: 'rateLimited', retryAfterSeconds: retryAfterSeconds(reason) ?? DEFAULT_RATE_LIMIT_WAIT_SECONDS };
  }
  const errors = fieldErrors(reason);
  if (errors.token) return { kind: 'invalid' };
  if (errors.newPassword) return { kind: 'password' };
  if (reason instanceof ApiError && (reason.uncertain || status === 0 || status >= 500)) return { kind: 'uncertain' };
  return { kind: 'failed' };
}

/** Whether the page is done with the link, so the in-memory token can be dropped. */
export function isFinalResetFailure(
  failure: ResetPasswordFailure,
): failure is Extract<ResetPasswordFailure, { kind: 'invalid' | 'expired' | 'unconfirmed' }> {
  return failure.kind === 'invalid' || failure.kind === 'expired' || failure.kind === 'unconfirmed';
}
