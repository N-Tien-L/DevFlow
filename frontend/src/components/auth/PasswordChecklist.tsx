import { PASSWORD_MAX_BYTES, PASSWORD_MAX_LENGTH, passwordRules } from '../../auth/validation';

/** Live new-password rules, shared by registration and password reset. */
export function PasswordChecklist({ password }: { password: string }) {
  const rules = passwordRules(password);
  const items: [boolean, string][] = [
    [rules.length, `8–${PASSWORD_MAX_LENGTH} ký tự`],
    [rules.uppercase, 'Có chữ hoa (A–Z)'],
    [rules.lowercase, 'Có chữ thường (a–z)'],
    [rules.digit, 'Có chữ số (0–9)'],
  ];
  // Only surfaces when it matters: a password of accented letters can fit the character limit but not the bytes.
  if (!rules.withinByteLimit) items.push([false, `Tối đa ${PASSWORD_MAX_BYTES} byte (chữ có dấu tính 2–3 byte)`]);
  return (
    <ul className="grid grid-cols-1 gap-1 min-[400px]:grid-cols-2">
      {items.map(([met, label]) => (
        <li key={label} className={met ? 'text-emerald-700 dark:text-emerald-400' : undefined}>
          <span aria-hidden="true">{met ? '✓' : '○'}</span> {label}
          <span className="sr-only">{met ? ': đã đạt' : ': chưa đạt'}</span>
        </li>
      ))}
    </ul>
  );
}
