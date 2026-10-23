import { useState } from 'react';
import { resendVerification } from '../../api/authApi';
import { AuthErrorCode, problemCode, retryAfterSeconds } from '../../api/problemDetail';
import { authErrorMessage } from '../../auth/authErrorMessage';
import { validateEmail } from '../../auth/validation';
import { useCountdown } from '../../hooks/useCountdown';
import { FormAlert, SecondaryButton, TextField } from './FormControls';

// Client-side pause between resends; the server enforces its own rate limit and answers 429 with Retry-After.
const RESEND_COOLDOWN_SECONDS = 60;

interface ResendVerificationProps {
  /** Known address (after register or a blocked login). When absent the user types it. */
  email?: string;
}

export function ResendVerification({ email: knownEmail }: ResendVerificationProps) {
  const [email, setEmail] = useState(knownEmail ?? '');
  const [busy, setBusy] = useState(false);
  const [fieldError, setFieldError] = useState<string>();
  const [result, setResult] = useState<{ tone: 'success' | 'error'; message: string } | null>(null);
  const cooldown = useCountdown();

  async function handleResend() {
    const target = knownEmail ?? email;
    const invalid = validateEmail(target);
    setFieldError(invalid);
    if (invalid) return;
    setBusy(true);
    setResult(null);
    try {
      await resendVerification(target);
      // 202 never reveals whether the address is registered, so the copy stays neutral.
      setResult({ tone: 'success', message: 'Nếu email này đang chờ xác minh, một liên kết mới đã được gửi. Hãy kiểm tra cả thư mục spam.' });
      cooldown.start(RESEND_COOLDOWN_SECONDS);
    } catch (reason) {
      if (problemCode(reason) === AuthErrorCode.RateLimitExceeded) cooldown.start(retryAfterSeconds(reason) ?? RESEND_COOLDOWN_SECONDS);
      setResult({ tone: 'error', message: authErrorMessage(reason, 'Không gửi lại được email xác minh. Vui lòng thử lại.') });
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="space-y-3">
      {!knownEmail && (
        <TextField
          label="Email đăng ký"
          type="email"
          autoComplete="email"
          inputMode="email"
          maxLength={254}
          value={email}
          error={fieldError}
          onChange={(event) => setEmail(event.target.value)}
        />
      )}
      <SecondaryButton className="w-full" disabled={busy || cooldown.active} onClick={() => void handleResend()}>
        {busy ? 'Đang gửi…' : cooldown.active ? `Gửi lại sau ${cooldown.remaining} giây` : 'Gửi lại email xác minh'}
      </SecondaryButton>
      {result && <FormAlert tone={result.tone}>{result.message}</FormAlert>}
    </div>
  );
}
