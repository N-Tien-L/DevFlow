import { useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { AuthErrorCode, problemCode, retryAfterSeconds } from '../api/problemDetail';
import { useAuth } from '../auth/AuthProvider';
import { authErrorMessage } from '../auth/authErrorMessage';
import { emailFromState, loginNoticeFromState } from '../auth/routerState';
import { EMAIL_MAX_LENGTH, validateLogin, type FieldErrors } from '../auth/validation';
import { AuthLayout } from '../components/auth/AuthLayout';
import { FormAlert, PasswordField, SubmitButton, TextField, linkClass } from '../components/auth/FormControls';
import { ResendVerification } from '../components/auth/ResendVerification';
import { LockIcon } from '../components/common/Icons';
import { useCountdown } from '../hooks/useCountdown';
import { ApiError } from '../types/board';

type LoginField = 'email' | 'password';

export default function LoginPage() {
  const { signIn, sessionEnd } = useAuth();
  const location = useLocation();
  // Handed over through router state (register 409, "check your inbox", forgot password); never storage or the URL.
  const [email, setEmail] = useState(() => emailFromState(location.state));
  const [notice] = useState(() => loginNoticeFromState(location.state));
  const [password, setPassword] = useState('');
  const [submitted, setSubmitted] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [unverifiedEmail, setUnverifiedEmail] = useState<string | null>(null);
  const wait = useCountdown();
  const inputs = useRef<Partial<Record<LoginField, HTMLInputElement | null>>>({});

  const clientErrors: FieldErrors<LoginField> = submitted ? validateLogin({ email, password }) : {};

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy || wait.active) return;
    setSubmitted(true);
    const errors = validateLogin({ email, password });
    const firstInvalid = (['email', 'password'] as const).find((field) => errors[field]);
    if (firstInvalid) {
      inputs.current[firstInvalid]?.focus();
      return;
    }
    setBusy(true);
    setError(null);
    setUnverifiedEmail(null);
    try {
      await signIn(email, password);
    } catch (reason) {
      const code = problemCode(reason);
      if (code === AuthErrorCode.EmailNotVerified) {
        setUnverifiedEmail(email.trim().toLowerCase());
      } else {
        if (code === AuthErrorCode.RateLimitExceeded) wait.start(retryAfterSeconds(reason) ?? 30);
        setError(reason instanceof ApiError && reason.status === 401 && !code
          ? 'Email hoặc mật khẩu không đúng.'
          : authErrorMessage(reason, 'Đăng nhập thất bại. Vui lòng thử lại.'));
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <AuthLayout
      title="Chào mừng trở lại"
      description="Đăng nhập để tiếp tục theo dõi board, tiến độ sprint và hoạt động Git/CI của nhóm."
      footer={(
        <div className="flex flex-wrap items-center justify-between gap-2">
          <span>Chưa có tài khoản DevFlow?</span>
          <Link className={linkClass} to="/register">Tạo tài khoản mới</Link>
        </div>
      )}
    >
      {notice === 'passwordReset' && (
        <FormAlert tone="success" className="mb-5">
          Mật khẩu đã được đặt lại. Mọi phiên đăng nhập cũ đã bị đăng xuất; hãy đăng nhập bằng mật khẩu mới.
        </FormAlert>
      )}
      {sessionEnd === 'expired' && notice !== 'passwordReset' && (
        <FormAlert tone="warning" className="mb-5">
          Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại để tiếp tục công việc.
        </FormAlert>
      )}

      <form className="space-y-4" onSubmit={handleSubmit} noValidate>
        <TextField
          label="Email"
          type="email"
          placeholder="ban@congty.vn"
          inputMode="email"
          autoComplete="username"
          autoCapitalize="none"
          spellCheck={false}
          maxLength={EMAIL_MAX_LENGTH}
          value={email}
          inputRef={(element) => { inputs.current.email = element; }}
          error={clientErrors.email}
          onChange={(event) => setEmail(event.target.value)}
          required
        />
        <PasswordField
          label="Mật khẩu"
          placeholder="Nhập mật khẩu của bạn"
          autoComplete="current-password"
          value={password}
          inputRef={(element) => { inputs.current.password = element; }}
          error={clientErrors.password}
          onChange={(event) => setPassword(event.target.value)}
          required
        />
        <div className="flex justify-end">
          <Link className={`${linkClass} text-sm`} to="/forgot-password" state={{ email: email.trim().toLowerCase() }}>
            Quên mật khẩu?
          </Link>
        </div>

        {error && <FormAlert tone="error">{error}</FormAlert>}

        <SubmitButton busy={busy} busyLabel="Đang đăng nhập…" disabled={wait.active}>
          {wait.active ? `Thử lại sau ${wait.remaining} giây` : 'Đăng nhập'}
        </SubmitButton>
      </form>

      {unverifiedEmail && (
        <div className="mt-5 space-y-3 border-t border-slate-200/80 pt-5 dark:border-slate-800">
          <FormAlert tone="warning">
            Email <strong className="break-all font-semibold">{unverifiedEmail}</strong> chưa được xác minh. Hãy mở liên kết trong hộp thư, hoặc gửi lại email xác minh.
          </FormAlert>
          <ResendVerification key={unverifiedEmail} email={unverifiedEmail} />
        </div>
      )}

      <p className="mt-5 flex items-center gap-2 rounded-xl bg-slate-50 px-3 py-2.5 text-xs leading-5 text-slate-600 ring-1 ring-inset ring-slate-200/70 dark:bg-slate-950/60 dark:text-slate-400 dark:ring-slate-800">
        <LockIcon className="size-3.5 shrink-0 text-slate-500" />
        <span>Phiên đăng nhập được lưu trong tab hiện tại và tự động xóa khi đăng xuất.</span>
      </p>
    </AuthLayout>
  );
}
