import clsx from 'clsx';
import { PASSWORD_MAX_BYTES, PASSWORD_MAX_LENGTH, passwordRules } from '../../auth/validation';
import { CheckCircleIcon } from '../common/Icons';

/** Live new-password rules, shared by registration and password reset. */
export function PasswordChecklist({ password }: { password: string }) {
  const rules = passwordRules(password);
  const baseItems: [boolean, string][] = [
    [rules.length, `8–${PASSWORD_MAX_LENGTH} ký tự`],
    [rules.uppercase, 'Có chữ hoa (A–Z)'],
    [rules.lowercase, 'Có chữ thường (a–z)'],
    [rules.digit, 'Có chữ số (0–9)'],
  ];
  const metCount = baseItems.filter(([met]) => met).length;
  const items: [boolean, string][] = [...baseItems];
  // Only surfaces when it matters: a password of accented letters can fit the character limit but not the bytes.
  if (!rules.withinByteLimit) items.push([false, `Tối đa ${PASSWORD_MAX_BYTES} byte (chữ có dấu tính 2–3 byte)`]);

  return (
    <div className="rounded-xl border border-slate-200/80 bg-slate-50/75 p-3 dark:border-slate-800 dark:bg-slate-950/60">
      <div className="mb-2 flex items-center justify-between gap-2">
        <span className="text-[11px] font-semibold text-slate-600 dark:text-slate-400">Yêu cầu mật khẩu</span>
        <span className="tabular-nums text-[11px] font-semibold text-slate-600 dark:text-slate-400">
          {metCount}/4
        </span>
      </div>
      <div className="mb-2.5 grid grid-cols-4 gap-1.5" aria-hidden="true">
        {[0, 1, 2, 3].map((step) => (
          <span
            key={step}
            className={clsx(
              'h-1 rounded-full transition-colors',
              step < metCount
                ? metCount === 4 && rules.withinByteLimit
                  ? 'bg-emerald-500'
                  : 'bg-indigo-500'
                : 'bg-slate-200 dark:bg-slate-800',
            )}
          />
        ))}
      </div>
      <ul className="grid grid-cols-1 gap-1.5 min-[400px]:grid-cols-2">
        {items.map(([met, label]) => (
          <li
            key={label}
            className={clsx(
              'flex items-center gap-1.5 text-xs',
              met ? 'font-medium text-emerald-700 dark:text-emerald-400' : 'text-slate-600 dark:text-slate-400',
            )}
          >
            {met ? (
              <CheckCircleIcon className="size-3.5 shrink-0 text-emerald-600 dark:text-emerald-400" />
            ) : (
              <span className="grid size-3.5 shrink-0 place-items-center" aria-hidden="true">
                <span className="size-1.5 rounded-full bg-slate-300 dark:bg-slate-600" />
              </span>
            )}
            <span>{label}</span>
            <span className="sr-only">{met ? ': đã đạt' : ': chưa đạt'}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}
