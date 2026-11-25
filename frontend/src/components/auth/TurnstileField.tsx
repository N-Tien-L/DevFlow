import type { useTurnstile } from '../../hooks/useTurnstile';
import { ShieldCheckIcon } from '../common/Icons';
import { LoadingSpinner } from '../common/UiPrimitives';
import { linkClass } from './FormControls';

/** The visible Turnstile widget with its loading, expiry and load-failure messages. */
export function TurnstileField({ turnstile }: { turnstile: ReturnType<typeof useTurnstile> }) {
  if (!turnstile.enabled) return null;
  return (
    <div className="rounded-xl border border-slate-200/90 bg-slate-50/75 p-3 dark:border-slate-800 dark:bg-slate-950/60">
      <div className="mb-2 flex items-center gap-1.5 text-xs font-semibold text-slate-600 dark:text-slate-400">
        <ShieldCheckIcon className="size-3.5 text-indigo-600 dark:text-indigo-400" />
        <span>Kiểm tra bảo mật</span>
      </div>
      <div ref={turnstile.hostRef} className="min-h-[65px] overflow-x-auto" />
      {turnstile.status === 'loading' && (
        <p className="mt-1 flex items-center gap-2 text-xs text-slate-600 dark:text-slate-400" role="status">
          <LoadingSpinner className="size-3.5 text-indigo-600" />
          <span>Đang tải bước kiểm tra bảo mật…</span>
        </p>
      )}
      {turnstile.status === 'expired' && (
        <p className="mt-2 text-xs font-medium text-amber-800 dark:text-amber-300" role="status">
          Kết quả kiểm tra bảo mật đã hết hạn, vui lòng xác minh lại.
        </p>
      )}
      {turnstile.status === 'error' && (
        <p className="mt-2 text-xs font-medium text-rose-600 dark:text-rose-400" role="alert">
          Không tải được bước kiểm tra bảo mật.{' '}
          <button type="button" className={linkClass} onClick={turnstile.reset}>
            Thử lại
          </button>
        </p>
      )}
    </div>
  );
}
