import type { useTurnstile } from '../../hooks/useTurnstile';
import { linkClass } from './FormControls';

/** The visible Turnstile widget with its loading, expiry and load-failure messages. */
export function TurnstileField({ turnstile }: { turnstile: ReturnType<typeof useTurnstile> }) {
  if (!turnstile.enabled) return null;
  return (
    <div>
      <div ref={turnstile.hostRef} className="min-h-[65px]" />
      {turnstile.status === 'loading' && <p className="text-xs text-slate-500 dark:text-slate-400" role="status">Đang tải bước kiểm tra bảo mật…</p>}
      {turnstile.status === 'expired' && <p className="mt-2 text-sm text-amber-700 dark:text-amber-300" role="status">Kết quả kiểm tra bảo mật đã hết hạn, vui lòng xác minh lại.</p>}
      {turnstile.status === 'error' && (
        <p className="mt-2 text-sm text-rose-600 dark:text-rose-400" role="alert">
          Không tải được bước kiểm tra bảo mật.{' '}
          <button type="button" className={linkClass} onClick={turnstile.reset}>Thử lại</button>
        </p>
      )}
    </div>
  );
}
