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
      description="Đăng nhập để tiếp tục quản lý board của nhóm."
      footer={<>Chưa có tài khoản? <Link className={linkClass} to="/register">Tạo tài khoản</Link></>}
    >
      {notice === 'passwordReset' && (
        <FormAlert tone="success" className="mb-5">Mật khẩu đã được đặt lại. Mọi phiên đăng nhập cũ đã bị đăng xuất; hãy đăng nhập bằng mật khẩu mới.</FormAlert>
      )}
      {sessionEnd === 'expired' && notice !== 'passwordReset' && (
        <FormAlert tone="warning" className="mb-5">Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại để tiếp tục.</FormAlert>
      )}

      <form className="space-y-5" onSubmit={handleSubmit} noValidate>
        <TextField
          label="Email"
          type="email"
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
          autoComplete="current-password"
          value={password}
          inputRef={(element) => { inputs.current.password = element; }}
          error={clientErrors.password}
          onChange={(event) => setPassword(event.target.value)}
          required
        />
        <p className="-mt-2 text-right text-sm">
          <Link className={linkClass} to="/forgot-password" state={{ email: email.trim().toLowerCase() }}>Quên mật khẩu?</Link>
        </p>

        {error && <FormAlert tone="error">{error}</FormAlert>}

        <SubmitButton busy={busy} busyLabel="Đang đăng nhập…" disabled={wait.active}>
          {wait.active ? `Thử lại sau ${wait.remaining} giây` : 'Đăng nhập'}
        </SubmitButton>
      </form>

      {unverifiedEmail && (
        <div className="mt-6 space-y-3">
          <FormAlert tone="warning">
            Email <strong className="break-all font-semibold">{unverifiedEmail}</strong> chưa được xác minh. Hãy mở liên kết trong hộp thư, hoặc gửi lại email xác minh.
          </FormAlert>
          <ResendVerification key={unverifiedEmail} email={unverifiedEmail} />
        </div>
      )}

      <p className="mt-6 text-xs leading-5 text-slate-500 dark:text-slate-400">Phiên đăng nhập được lưu trong tab hiện tại và xóa khi đăng xuất.</p>
    </AuthLayout>
  );
}
