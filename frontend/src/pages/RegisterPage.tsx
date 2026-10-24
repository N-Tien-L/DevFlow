import { useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { register } from '../api/authApi';
import { AuthErrorCode, fieldErrors, problemCode, retryAfterSeconds } from '../api/problemDetail';
import { authErrorMessage } from '../auth/authErrorMessage';
import {
  EMAIL_MAX_LENGTH,
  FULL_NAME_MAX_LENGTH,
  PASSWORD_MAX_LENGTH,
  validateRegistration,
  type FieldErrors,
  type RegisterField,
} from '../auth/validation';
import { AuthLayout } from '../components/auth/AuthLayout';
import { FormAlert, PasswordField, SecondaryButton, SubmitButton, TextField, linkClass } from '../components/auth/FormControls';
import { PasswordChecklist } from '../components/auth/PasswordChecklist';
import { ResendVerification } from '../components/auth/ResendVerification';
import { TurnstileField } from '../components/auth/TurnstileField';
import { useCountdown } from '../hooks/useCountdown';
import { useTurnstile } from '../hooks/useTurnstile';
import type { RegisterResponse } from '../types/auth';
import { ApiError } from '../types/board';

type Values = Record<RegisterField, string>;

const FIELD_ORDER: RegisterField[] = ['fullName', 'email', 'password'];
const EMPTY: Values = { fullName: '', email: '', password: '' };

interface FormFailure {
  message: string;
  /** 409: the address already has an account. */
  existingAccount?: boolean;
}

function serverFieldErrors(reason: unknown, values: Values): FieldErrors<RegisterField> {
  const code = problemCode(reason);
  const raw = fieldErrors(reason);
  const errors: FieldErrors<RegisterField> = {};
  const local = validateRegistration(values);
  for (const field of FIELD_ORDER) {
    if (!raw[field]) continue;
    // Server messages are English; prefer our Vietnamese copy for the same rule.
    errors[field] = field === 'email' && (code === AuthErrorCode.DisposableEmail || code === AuthErrorCode.EmailDomainUndeliverable)
      ? authErrorMessage(reason)
      : local[field] ?? 'Giá trị này không hợp lệ. Vui lòng kiểm tra lại.';
  }
  return errors;
}

function RegisterForm({ onRegistered }: { onRegistered(response: RegisterResponse): void }) {
  const [values, setValues] = useState<Values>(EMPTY);
  const [touched, setTouched] = useState<Partial<Record<RegisterField, boolean>>>({});
  const [submitted, setSubmitted] = useState(false);
  const [serverErrors, setServerErrors] = useState<FieldErrors<RegisterField>>({});
  const [failure, setFailure] = useState<FormFailure | null>(null);
  // `missing` hides itself once the widget produces a token; server rejections stay until the next submit.
  const [botMessage, setBotMessage] = useState<{ text: string; missing?: boolean } | null>(null);
  const [busy, setBusy] = useState(false);
  const wait = useCountdown();
  const turnstile = useTurnstile('register');
  const inputs = useRef<Partial<Record<RegisterField, HTMLInputElement | null>>>({});

  const clientErrors = validateRegistration(values);
  const visibleError = (field: RegisterField) => serverErrors[field] ?? ((submitted || touched[field]) ? clientErrors[field] : undefined);

  function update(field: RegisterField, value: string) {
    setValues((current) => ({ ...current, [field]: value }));
    setServerErrors((current) => ({ ...current, [field]: undefined }));
  }

  function focusFirst(errors: FieldErrors<RegisterField>) {
    const field = FIELD_ORDER.find((name) => errors[name]);
    if (field) inputs.current[field]?.focus();
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy || wait.active) return;
    setSubmitted(true);
    setFailure(null);
    setBotMessage(null);
    if (Object.keys(clientErrors).length > 0) {
      focusFirst(clientErrors);
      return;
    }
    if (!turnstile.satisfied) {
      setBotMessage(turnstile.status === 'misconfigured'
        ? { text: 'Thiếu cấu hình kiểm tra bảo mật nên chưa thể đăng ký. Vui lòng liên hệ quản trị viên.' }
        : { text: 'Vui lòng hoàn tất bước kiểm tra bảo mật bên dưới.', missing: true });
      return;
    }
    setBusy(true);
    try {
      const response = await register({ ...values, turnstileToken: turnstile.token });
      onRegistered(response);
    } catch (reason) {
      const code = problemCode(reason);
      const status = reason instanceof ApiError ? reason.status : 0;
      const fieldMessages = serverFieldErrors(reason, values);
      if (Object.keys(fieldMessages).length > 0) {
        setServerErrors(fieldMessages);
        focusFirst(fieldMessages);
      } else if (code === AuthErrorCode.BotChallengeFailed) {
        setBotMessage({ text: authErrorMessage(reason) });
      } else if (status === 409) {
        setFailure({ message: 'Email này đã có tài khoản.', existingAccount: true });
        inputs.current.email?.focus();
      } else {
        if (code === AuthErrorCode.RateLimitExceeded || code === AuthErrorCode.BotProtectionUnavailable) {
          wait.start(retryAfterSeconds(reason) ?? 30);
        }
        setFailure({ message: authErrorMessage(reason, 'Không tạo được tài khoản. Vui lòng thử lại.') });
      }
    } finally {
      setBusy(false);
      // Turnstile tokens are single-use: always request a fresh one after a submit.
      turnstile.reset();
    }
  }

  const bindRef = (field: RegisterField) => (element: HTMLInputElement | null) => { inputs.current[field] = element; };
  const blur = (field: RegisterField) => () => setTouched((current) => ({ ...current, [field]: true }));

  return (
    <form className="space-y-5" onSubmit={handleSubmit} noValidate>
      <TextField
        label="Họ và tên"
        autoComplete="name"
        maxLength={FULL_NAME_MAX_LENGTH}
        value={values.fullName}
        inputRef={bindRef('fullName')}
        error={visibleError('fullName')}
        onBlur={blur('fullName')}
        onChange={(event) => update('fullName', event.target.value)}
        required
      />
      <TextField
        label="Email"
        type="email"
        inputMode="email"
        autoComplete="email"
        autoCapitalize="none"
        spellCheck={false}
        maxLength={EMAIL_MAX_LENGTH}
        value={values.email}
        inputRef={bindRef('email')}
        error={visibleError('email')}
        onBlur={blur('email')}
        onChange={(event) => update('email', event.target.value)}
        required
      />
      <PasswordField
        label="Mật khẩu"
        autoComplete="new-password"
        maxLength={PASSWORD_MAX_LENGTH}
        value={values.password}
        inputRef={bindRef('password')}
        error={visibleError('password')}
        hint={<PasswordChecklist password={values.password} />}
        onBlur={blur('password')}
        onChange={(event) => update('password', event.target.value)}
        required
      />

      <TurnstileField turnstile={turnstile} />
      {botMessage && !(botMessage.missing && turnstile.satisfied) && <FormAlert tone="error">{botMessage.text}</FormAlert>}

      {failure && (
        <FormAlert tone="error">
          {failure.message}
          {failure.existingAccount && (
            <> <Link className={linkClass} to="/login" state={{ email: values.email.trim().toLowerCase() }}>Đăng nhập</Link> hoặc dùng email khác.</>
          )}
        </FormAlert>
      )}

      <SubmitButton busy={busy} busyLabel="Đang tạo tài khoản…" disabled={wait.active || turnstile.status === 'misconfigured'}>
        {wait.active ? `Thử lại sau ${wait.remaining} giây` : 'Tạo tài khoản'}
      </SubmitButton>
    </form>
  );
}

export default function RegisterPage() {
  const [registered, setRegistered] = useState<RegisterResponse | null>(null);
  const [formKey, setFormKey] = useState(0);
  const headingRef = useRef<HTMLHeadingElement>(null);

  useEffect(() => {
    headingRef.current?.focus();
  }, [registered]);

  if (registered) {
    return (
      <AuthLayout
        headingRef={headingRef}
        title="Kiểm tra hộp thư"
        description={<>Chúng tôi đã gửi liên kết xác minh tới <strong className="break-all font-semibold text-slate-800 dark:text-slate-100">{registered.email}</strong>. Mở liên kết để kích hoạt tài khoản, sau đó đăng nhập.</>}
        footer={<>Đã xác minh? <Link className={linkClass} to="/login" state={{ email: registered.email }}>Đăng nhập</Link></>}
      >
        <div className="space-y-4">
          <p className="text-sm leading-6 text-slate-600 dark:text-slate-300">Không thấy email? Hãy kiểm tra thư mục spam hoặc gửi lại. Liên kết có thời hạn và chỉ dùng được một lần.</p>
          <ResendVerification email={registered.email} />
          <SecondaryButton className="w-full" onClick={() => { setRegistered(null); setFormKey((value) => value + 1); }}>
            Dùng email khác
          </SecondaryButton>
        </div>
      </AuthLayout>
    );
  }

  return (
    <AuthLayout
      headingRef={headingRef}
      title="Tạo tài khoản"
      description="Bắt đầu điều phối công việc của nhóm trên DevFlow."
      footer={<>Đã có tài khoản? <Link className={linkClass} to="/login">Đăng nhập</Link></>}
    >
      <RegisterForm key={formKey} onRegistered={setRegistered} />
    </AuthLayout>
  );
}
