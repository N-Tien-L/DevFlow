import { useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { forgotPassword } from '../api/authApi';
import { authErrorMessage } from '../auth/authErrorMessage';
import { forgotPasswordFailure } from '../auth/passwordReset';
import { emailFromState } from '../auth/routerState';
import { EMAIL_MAX_LENGTH, validateForgotPassword } from '../auth/validation';
import { AuthLayout } from '../components/auth/AuthLayout';
import { FormAlert, SecondaryButton, SubmitButton, TextField, linkClass } from '../components/auth/FormControls';
import { TurnstileField } from '../components/auth/TurnstileField';
import { useCountdown } from '../hooks/useCountdown';
import { useTurnstile } from '../hooks/useTurnstile';

function ForgotPasswordForm({ initialEmail, onSent }: { initialEmail: string; onSent(email: string): void }) {
  const [email, setEmail] = useState(initialEmail);
  const [touched, setTouched] = useState(false);
  const [submitted, setSubmitted] = useState(false);
  const [serverEmailError, setServerEmailError] = useState<string | null>(null);
  // `missing` hides itself once the widget produces a token; server rejections stay until the next submit.
  const [botMessage, setBotMessage] = useState<{ text: string; missing?: boolean } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const wait = useCountdown();
  const turnstile = useTurnstile('forgot_password');
  const emailRef = useRef<HTMLInputElement>(null);

  const clientError = validateForgotPassword({ email }).email;
  const visibleError = serverEmailError ?? ((submitted || touched) ? clientError : undefined);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy || wait.active) return;
    setSubmitted(true);
    setError(null);
    setBotMessage(null);
    if (clientError) {
      emailRef.current?.focus();
      return;
    }
    if (!turnstile.satisfied) {
      setBotMessage(turnstile.status === 'misconfigured'
        ? { text: 'Thiếu cấu hình kiểm tra bảo mật nên chưa thể gửi yêu cầu. Vui lòng liên hệ quản trị viên.' }
        : { text: 'Vui lòng hoàn tất bước kiểm tra bảo mật bên dưới.', missing: true });
      return;
    }
    setBusy(true);
    try {
      await forgotPassword(email, turnstile.token);
      onSent(email.trim().toLowerCase());
    } catch (reason) {
      const failure = forgotPasswordFailure(reason);
      switch (failure.kind) {
        case 'email':
          setServerEmailError('Email không đúng định dạng.');
          emailRef.current?.focus();
          break;
        case 'botChallenge':
          setBotMessage({ text: 'Không xác minh được bạn là người thật. Vui lòng thực hiện lại bước kiểm tra bảo mật.' });
          break;
        case 'wait':
          wait.start(failure.retryAfterSeconds);
          setError(authErrorMessage(reason));
          break;
        case 'failed':
          setError(authErrorMessage(reason, 'Không gửi được yêu cầu. Vui lòng thử lại.'));
          break;
      }
    } finally {
      setBusy(false);
      // Turnstile tokens are single-use: always request a fresh one after a submit.
      turnstile.reset();
    }
  }

  return (
    <form className="space-y-5" onSubmit={handleSubmit} noValidate>
      <TextField
        label="Email"
        type="email"
        inputMode="email"
        autoComplete="email"
        autoCapitalize="none"
        spellCheck={false}
        maxLength={EMAIL_MAX_LENGTH}
        value={email}
        inputRef={emailRef}
        error={visibleError}
        onBlur={() => setTouched(true)}
        onChange={(event) => {
          setEmail(event.target.value);
          setServerEmailError(null);
        }}
        required
      />

      <TurnstileField turnstile={turnstile} />
      {botMessage && !(botMessage.missing && turnstile.satisfied) && <FormAlert tone="error">{botMessage.text}</FormAlert>}

      {error && <FormAlert tone="error">{error}</FormAlert>}

      <SubmitButton busy={busy} busyLabel="Đang gửi…" disabled={wait.active || turnstile.status === 'misconfigured'}>
        {wait.active ? `Thử lại sau ${wait.remaining} giây` : 'Gửi liên kết đặt lại'}
      </SubmitButton>
    </form>
  );
}

export default function ForgotPasswordPage() {
  const location = useLocation();
  const [sentTo, setSentTo] = useState<string | null>(null);
  const [lastEmail, setLastEmail] = useState(() => emailFromState(location.state));
  const [formKey, setFormKey] = useState(0);
  const headingRef = useRef<HTMLHeadingElement>(null);

  useEffect(() => {
    headingRef.current?.focus();
  }, [sentTo]);

  const backToLogin = <Link className={linkClass} to="/login" state={{ email: sentTo ?? lastEmail }}>Quay lại đăng nhập</Link>;

  if (sentTo) {
    return (
      <AuthLayout
        headingRef={headingRef}
        title="Kiểm tra hộp thư"
        // Identical whether or not the address has an account: the page must not reveal which emails are registered.
        description={<>Nếu <strong className="break-all font-semibold text-slate-800 dark:text-slate-100">{sentTo}</strong> có tài khoản DevFlow, chúng tôi đã gửi liên kết đặt lại mật khẩu tới địa chỉ này.</>}
        footer={backToLogin}
      >
        <div className="space-y-4">
          <p className="text-sm leading-6 text-slate-600 dark:text-slate-300">
            Liên kết có thời hạn và chỉ dùng được một lần; yêu cầu mới sẽ vô hiệu hóa liên kết cũ. Không thấy email? Hãy kiểm tra thư mục spam hoặc gửi lại yêu cầu sau ít phút.
          </p>
          <SecondaryButton className="w-full" onClick={() => { setSentTo(null); setFormKey((value) => value + 1); }}>
            Gửi lại hoặc dùng email khác
          </SecondaryButton>
        </div>
      </AuthLayout>
    );
  }

  return (
    <AuthLayout
      headingRef={headingRef}
      title="Quên mật khẩu"
      description="Nhập email tài khoản của bạn. Chúng tôi sẽ gửi liên kết để đặt mật khẩu mới."
      footer={<>Nhớ ra mật khẩu? {backToLogin}</>}
    >
      <ForgotPasswordForm
        key={formKey}
        initialEmail={lastEmail}
        onSent={(email) => {
          setLastEmail(email);
          setSentTo(email);
        }}
      />
    </AuthLayout>
  );
}
