import { AuthErrorCode, problemCode, retryAfterSeconds } from '../api/problemDetail';
import { ApiError } from '../types/board';

export const InvitationErrorCode = {
  Invalid: 'INVITATION_INVALID',
  Expired: 'INVITATION_EXPIRED',
  EmailMismatch: 'INVITATION_EMAIL_MISMATCH',
} as const;

const TOKEN_MAX_LENGTH = 128;

// The invitation token lives only in this module: never in storage, history.state or a URL. It survives
// the in-app trip to /login and back, and is lost on reload (the user then reopens the email link).
let pendingToken: string | null = null;

/** Takes `?token=` from the current address (if any) into memory and returns the token to use. */
export function captureInvitationToken(search: string): string | null {
  const fromUrl = new URLSearchParams(search).get('token')?.trim();
  if (fromUrl && fromUrl.length <= TOKEN_MAX_LENGTH) pendingToken = fromUrl;
  return pendingToken;
}

export function clearInvitationToken() {
  pendingToken = null;
}

export type InvitationFailure =
  | { kind: 'invalid' }
  | { kind: 'expired' }
  | { kind: 'emailMismatch' }
  | { kind: 'rateLimited'; retryAfterSeconds: number }
  /** No definite answer (network, timeout, 5xx): the invitation may already be accepted. */
  | { kind: 'uncertain' }
  | { kind: 'failed' };

/** Maps an accept failure to what the page shows. Status is the fallback when the body carries no code. */
export function invitationFailure(reason: unknown): InvitationFailure {
  const code = problemCode(reason);
  const status = reason instanceof ApiError ? reason.status : 0;
  if (code === InvitationErrorCode.Expired || (!code && status === 410)) return { kind: 'expired' };
  if (code === InvitationErrorCode.EmailMismatch) return { kind: 'emailMismatch' };
  if (code === InvitationErrorCode.Invalid || (!code && status === 400)) return { kind: 'invalid' };
  if (code === AuthErrorCode.RateLimitExceeded) return { kind: 'rateLimited', retryAfterSeconds: retryAfterSeconds(reason) ?? 60 };
  if (reason instanceof ApiError && (reason.uncertain || status === 0 || status >= 500)) return { kind: 'uncertain' };
  return { kind: 'failed' };
}

/** Whether the outcome ends the invitation for good, so the in-memory token can be dropped. */
export function isFinalFailure(failure: InvitationFailure) {
  return failure.kind === 'invalid' || failure.kind === 'expired';
}
