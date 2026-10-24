import { useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { resetPassword } from '../api/authApi';
import { useAuth } from '../auth/AuthProvider';
import { isFinalResetFailure, resetPasswordFailure, type ResetPasswordFailure } from '../auth/passwordReset';
import type { LoginNotice } from '../auth/routerState';
import { removeTokenFromAddressBar, tokenFromSearch } from '../auth/urlToken';
import {
  PASSWORD_MAX_LENGTH,
  validateNewPassword,
  validateResetPassword,
  type FieldErrors,
  type ResetPasswordField,
} from '../auth/validation';
import { AuthLayout } from '../components/auth/AuthLayout';
import { FormAlert, PasswordField, SubmitButton, linkClass } from '../components/auth/FormControls';
import { PasswordChecklist } from '../components/auth/PasswordChecklist';
import { useCountdown } from '../hooks/useCountdown';

type Values = Record<ResetPasswordField, string>;

const FIELD_ORDER: ResetPasswordField[] = ['newPassword', 'confirmPassword'];

const finalTitles: Record<'invalid' | 'expired' | 'unconfirmed', string> = {
  invalid: 'Liên kết không hợp lệ',
  expired: 'Liên kết đã hết hạn',
  unconfirmed: 'Chưa xác nhận được kết quả',
};

const secondaryLinkClass = 'block w-full rounded-xl border border-slate-300 px-4 py-3 text-center font-semibold text-slate-700 transition hover:bg-slate-50 focus:outline-none focus-visible:ring-4 focus-visible:ring-indigo-200 dark:border-slate-700 dark:text-slate-200 dark:hover:bg-slate-800 dark:focus-visible:ring-indigo-500/30';

const primaryLinkClass = 'block w-full rounded-xl bg-indigo-600 px-4 py-3 text-center font-semibold text-white shadow-lg shadow-indigo-600/20 transition hover:bg-indigo-700 focus:outline-none focus-visible:ring-4 focus-visible:ring-indigo-300 dark:shadow-none dark:focus-visible:ring-indigo-500/40';

function failureMessage(failure: ResetPasswordFailure) {
  switch (failure.kind) {
    case 'invalid':
      return 'Liên kết đặt lại mật khẩu không hợp lệ, đã được sử dụng hoặc đã bị thay bằng một liên kết mới hơn.';
    case 'expired':
      return 'Liên kết đặt lại mật khẩu đã hết hạn.';
    case 'password':
      return 'Mật khẩu mới chưa đáp ứng yêu cầu. Vui lòng kiểm tra lại.';
    case 'rateLimited':
      return 'Bạn thử quá nhiều lần. Vui lòng đợi rồi thử lại.';
    case 'uncertain':
      return 'Chưa rõ mật khẩu đã được đổi hay chưa do không nhận được phản hồi từ máy chủ. Hãy thử lại; nếu liên kết báo đã được sử dụng, hãy đăng nhập bằng mật khẩu mới.';
    case 'unconfirmed':
      return 'Máy chủ đã nhận yêu cầu nhưng phản hồi không đọc được, nên chưa xác nhận được kết quả. Mật khẩu có thể đã được đổi: hãy thử đăng nhập bằng mật khẩu mới, hoặc yêu cầu liên kết mới nếu không đăng nhập được.';
    case 'failed':
      return 'Không đặt lại được mật khẩu. Vui lòng thử lại.';
  }
}

export default function ResetPasswordPage() {
  const { signOut } = useAuth();
  const navigate = useNavigate();
  // A one-time credential: read once from the address bar, then kept only in this component's state.
  // Never stored, logged or put back into a URL; a reload loses it and the user reopens the email link.
  const [token, setToken] = useState(() => tokenFromSearch(window.location.search));
  const [values, setValues] = useState<Values>({ newPassword: '', confirmPassword: '' });
  const [touched, setTouched] = useState<Partial<Record<ResetPasswordField, boolean>>>({});
  const [submitted, setSubmitted] = useState(false);
  const [serverPasswordError, setServerPasswordError] = useState<string | null>(null);
  const [failure, setFailure] = useState<ResetPasswordFailure | null>(null);
  // After an unanswered attempt, "link already used" may just mean that attempt went through.
  const [hadUncertainAttempt, setHadUncertainAttempt] = useState(false);
  const [busy, setBusy] = useState(false);
  const wait = useCountdown();
  const headingRef = useRef<HTMLHeadingElement>(null);
  const inputs = useRef<Partial<Record<ResetPasswordField, HTMLInputElement | null>>>({});
  // The in-flight reset. Aborted on unmount: an answer that arrives after the user has left must not sign out
  // or redirect whoever is using the app by then (possibly a different account).
  const pending = useRef<AbortController | null>(null);

  useEffect(() => {
    // Strip the token from the address bar and this history entry before anything else can see it.
    removeTokenFromAddressBar();
  }, []);

  useEffect(() => () => pending.current?.abort(), []);

  const final = failure !== null && isFinalResetFailure(failure);
  useEffect(() => {
    if (final) headingRef.current?.focus();
  }, [final]);

  const clientErrors = validateResetPassword(values);
  const visibleError = (field: ResetPasswordField) => {
    if (field === 'newPassword' && serverPasswordError) return serverPasswordError;
    return (submitted || touched[field]) ? clientErrors[field] : undefined;
  };

  function update(field: ResetPasswordField, value: string) {
    setValues((current) => ({ ...current, [field]: value }));
    if (field === 'newPassword') setServerPasswordError(null);
  }

  function focusFirst(errors: FieldErrors<ResetPasswordField>) {
    const field = FIELD_ORDER.find((name) => errors[name]);
    if (field) inputs.current[field]?.focus();
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!token || busy || wait.active) return;
    setSubmitted(true);
    if (Object.keys(clientErrors).length > 0) {
      focusFirst(clientErrors);
      return;
    }
    // The previous message stays until the new outcome replaces it, so the button does not jump under a double-click.
    setBusy(true);
    const controller = new AbortController();
    pending.current = controller;
    try {
      await resetPassword(token, values.newPassword, controller.signal);
      if (controller.signal.aborted) return;
      setToken(null);
      // The server has revoked every session of the account; drop any session this tab still holds.
      signOut();
      const notice: LoginNotice = 'passwordReset';
      navigate('/login', { replace: true, state: { notice } });
    } catch (reason) {
      if (controller.signal.aborted) return;
      const next = resetPasswordFailure(reason);
      if (isFinalResetFailure(next)) setToken(null);
      // The password may have changed, which revokes every session; a stale one here would only fail later.
      if (next.kind === 'unconfirmed') signOut();
      if (next.kind === 'uncertain') setHadUncertainAttempt(true);
      if (next.kind === 'rateLimited') wait.start(next.retryAfterSeconds);
      if (next.kind === 'password') {
        setServerPasswordError(validateNewPassword(values.newPassword) ?? 'Mật khẩu mới chưa đáp ứng yêu cầu bảo mật. Vui lòng chọn mật khẩu khác.');
        inputs.current.newPassword?.focus();
        setFailure(null);
      } else {
        setFailure(next);
      }
    } finally {
      if (pending.current === controller) pending.current = null;
      if (!controller.signal.aborted) setBusy(false);
    }
  }

  const footer = <Link className={linkClass} to="/login">Quay lại đăng nhập</Link>;
  const requestNewLink = <Link className={primaryLinkClass} to="/forgot-password">Yêu cầu liên kết mới</Link>;

  if (failure && isFinalResetFailure(failure)) {
    if (failure.kind === 'unconfirmed') {
      return (
        <AuthLayout headingRef={headingRef} title={finalTitles.unconfirmed}>
          <div className="space-y-4">
            <FormAlert tone="warning">{failureMessage(failure)}</FormAlert>
            <Link className={primaryLinkClass} to="/login">Đăng nhập bằng mật khẩu mới</Link>
            <Link className={secondaryLinkClass} to="/forgot-password">Yêu cầu liên kết mới</Link>
          </div>
        </AuthLayout>
      );
    }
    return (
      <AuthLayout headingRef={headingRef} title={finalTitles[failure.kind]} footer={footer}>
        <div className="space-y-5">
          <FormAlert tone="error">
            {failureMessage(failure)}
            {hadUncertainAttempt && <> Nếu lần thử trước đã thành công, hãy <Link className={linkClass} to="/login">đăng nhập bằng mật khẩu mới</Link>.</>}
          </FormAlert>
          {requestNewLink}
        </div>
      </AuthLayout>
    );
  }

  if (!token) {
    return (
      <AuthLayout title="Không tìm thấy liên kết" footer={footer}>
        <div className="space-y-5">
          <FormAlert tone="error">
            Liên kết đặt lại mật khẩu không đầy đủ, hoặc trang đã được tải lại nên liên kết không còn trong bộ nhớ. Hãy mở lại liên kết trong email, hoặc yêu cầu liên kết mới.
          </FormAlert>
          {requestNewLink}
        </div>
      </AuthLayout>
    );
  }

  return (
    <AuthLayout
      headingRef={headingRef}
      title="Đặt mật khẩu mới"
      description="Sau khi đổi mật khẩu, mọi phiên đăng nhập hiện có của tài khoản sẽ bị đăng xuất."
      footer={footer}
    >
      <form className="space-y-5" onSubmit={handleSubmit} noValidate>
        <PasswordField
          label="Mật khẩu mới"
          autoComplete="new-password"
          maxLength={PASSWORD_MAX_LENGTH}
          value={values.newPassword}
          inputRef={(element) => { inputs.current.newPassword = element; }}
          error={visibleError('newPassword')}
          hint={<PasswordChecklist password={values.newPassword} />}
          onBlur={() => setTouched((current) => ({ ...current, newPassword: true }))}
          onChange={(event) => update('newPassword', event.target.value)}
          required
        />
        <PasswordField
          label="Nhập lại mật khẩu mới"
          autoComplete="new-password"
          maxLength={PASSWORD_MAX_LENGTH}
          value={values.confirmPassword}
          inputRef={(element) => { inputs.current.confirmPassword = element; }}
          error={visibleError('confirmPassword')}
          onBlur={() => setTouched((current) => ({ ...current, confirmPassword: true }))}
          onChange={(event) => update('confirmPassword', event.target.value)}
          required
        />

        {failure && <FormAlert tone="error">{failureMessage(failure)}</FormAlert>}

        <SubmitButton busy={busy} busyLabel="Đang đặt lại…" disabled={wait.active}>
          {wait.active ? `Thử lại sau ${wait.remaining} giây` : 'Đặt lại mật khẩu'}
        </SubmitButton>
      </form>
    </AuthLayout>
  );
}
