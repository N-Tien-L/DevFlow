import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { verifyEmail } from '../api/authApi';
import { AuthErrorCode, problemCode, retryAfterSeconds } from '../api/problemDetail';
import { useAuth } from '../auth/AuthProvider';
import { authErrorMessage } from '../auth/authErrorMessage';
import { AuthLayout } from '../components/auth/AuthLayout';
import { FormAlert, SecondaryButton, linkClass } from '../components/auth/FormControls';
import { ResendVerification } from '../components/auth/ResendVerification';
import { useCountdown } from '../hooks/useCountdown';
import type { MessageResponse } from '../types/auth';
import { ApiError } from '../types/board';

type Outcome =
  | { kind: 'verifying' }
  | { kind: 'verified' }
  | { kind: 'expired' }
  | { kind: 'invalid' }
  | { kind: 'retryable'; message: string };

const primaryLinkClass = 'block w-full rounded-xl bg-indigo-600 px-4 py-3 text-center font-semibold text-white shadow-lg shadow-indigo-600/20 transition hover:bg-indigo-700 focus:outline-none focus-visible:ring-4 focus-visible:ring-indigo-300 dark:shadow-none';

function outcomeOf(reason: unknown): Outcome {
  const code = problemCode(reason);
  const status = reason instanceof ApiError ? reason.status : 0;
  if (code === AuthErrorCode.VerificationTokenExpired || status === 410) return { kind: 'expired' };
  if (code === AuthErrorCode.VerificationTokenInvalid || status === 400) return { kind: 'invalid' };
  return { kind: 'retryable', message: authErrorMessage(reason, 'Chưa xác minh được email. Vui lòng thử lại.') };
}

const titles: Record<Outcome['kind'], string> = {
  verifying: 'Đang xác minh email',
  verified: 'Email đã được xác minh',
  expired: 'Liên kết đã hết hạn',
  invalid: 'Liên kết không hợp lệ',
  retryable: 'Chưa xác minh được',
};

export default function EmailVerificationPage() {
  const { user } = useAuth();
  // Read once, then keep only in memory: the token is a one-time credential.
  const [token] = useState(() => new URLSearchParams(window.location.search).get('token')?.trim() || null);
  const [outcome, setOutcome] = useState<Outcome>(() => token ? { kind: 'verifying' } : { kind: 'invalid' });
  const [attempt, setAttempt] = useState(0);
  // Shared across StrictMode's double effect run so a single-use token is posted only once per attempt.
  const requestRef = useRef<Promise<MessageResponse> | null>(null);
  const headingRef = useRef<HTMLHeadingElement>(null);
  const wait = useCountdown();
  const startWait = wait.start;

  useEffect(() => {
    // Remove the token from the address bar and history so it is not bookmarked, shared or leaked by Referer.
    if (new URLSearchParams(window.location.search).has('token')) {
      window.history.replaceState(window.history.state, '', window.location.pathname);
    }
  }, []);

  useEffect(() => {
    if (!token) return;
    let active = true;
    requestRef.current ??= verifyEmail(token);
    requestRef.current.then(() => {
      if (active) setOutcome({ kind: 'verified' });
    }).catch((reason: unknown) => {
      if (!active) return;
      if (problemCode(reason) === AuthErrorCode.RateLimitExceeded) startWait(retryAfterSeconds(reason) ?? 30);
      setOutcome(outcomeOf(reason));
    });
    return () => { active = false; };
  }, [attempt, startWait, token]);

  useEffect(() => {
    if (outcome.kind !== 'verifying') headingRef.current?.focus();
  }, [outcome.kind]);

  function retry() {
    requestRef.current = null;
    setOutcome({ kind: 'verifying' });
    setAttempt((value) => value + 1);
  }

  const loginLink = user
    ? <Link className={primaryLinkClass} to="/workspaces">Mở DevFlow</Link>
    : <Link className={primaryLinkClass} to="/login">Đăng nhập</Link>;

  return (
    <AuthLayout
      headingRef={headingRef}
      title={titles[outcome.kind]}
      footer={outcome.kind === 'verified' ? undefined : <>Đã xác minh trước đó? <Link className={linkClass} to="/login">Đăng nhập</Link></>}
    >
      {outcome.kind === 'verifying' && (
        <div className="flex items-center gap-3 text-sm text-slate-600 dark:text-slate-300" role="status">
          <span className="size-5 animate-spin rounded-full border-2 border-indigo-200 border-t-indigo-600 motion-reduce:animate-none dark:border-indigo-900 dark:border-t-indigo-400" aria-hidden="true" />
          Đang kiểm tra liên kết xác minh…
        </div>
      )}

      {outcome.kind === 'verified' && (
        <div className="space-y-5">
          <FormAlert tone="success">Tài khoản đã được kích hoạt. Bạn có thể đăng nhập ngay bây giờ.</FormAlert>
          {loginLink}
        </div>
      )}

      {outcome.kind === 'expired' && (
        <div className="space-y-5">
          <FormAlert tone="warning">Liên kết xác minh đã hết hạn. Nhập email đăng ký để nhận liên kết mới.</FormAlert>
          <ResendVerification />
        </div>
      )}

      {outcome.kind === 'invalid' && (
        <div className="space-y-5">
          <FormAlert tone="error">
            Liên kết xác minh không hợp lệ hoặc đã được sử dụng. Hãy mở đúng liên kết mới nhất trong email, hoặc gửi lại email xác minh.
          </FormAlert>
          <ResendVerification />
        </div>
      )}

      {outcome.kind === 'retryable' && (
        <div className="space-y-5">
          <FormAlert tone="error">{outcome.message}</FormAlert>
          <SecondaryButton className="w-full" disabled={wait.active} onClick={retry}>
            {wait.active ? `Thử lại sau ${wait.remaining} giây` : 'Thử lại'}
          </SecondaryButton>
        </div>
      )}
    </AuthLayout>
  );
}
