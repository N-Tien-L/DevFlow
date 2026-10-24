import { useId, useState } from 'react';
import type { ButtonHTMLAttributes, InputHTMLAttributes, PropsWithChildren, ReactNode, Ref } from 'react';
import clsx from 'clsx';

const inputClass = 'w-full rounded-xl border bg-white px-4 py-3 text-base text-slate-900 outline-none transition placeholder:text-slate-400 focus:ring-4 disabled:cursor-not-allowed disabled:opacity-60 sm:text-sm dark:bg-slate-950 dark:text-slate-100 dark:placeholder:text-slate-500';
const validInputClass = 'border-slate-300 focus:border-indigo-500 focus:ring-indigo-100 dark:border-slate-700 dark:focus:border-indigo-400 dark:focus:ring-indigo-500/25';
const invalidInputClass = 'border-rose-400 focus:border-rose-500 focus:ring-rose-100 dark:border-rose-500/70 dark:focus:ring-rose-500/25';

export const linkClass = 'font-semibold text-indigo-600 underline-offset-4 hover:underline focus-visible:rounded focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 dark:text-indigo-400';

interface TextFieldProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'id'> {
  label: string;
  error?: string;
  hint?: ReactNode;
  inputRef?: Ref<HTMLInputElement>;
  /** Rendered inside the input row, e.g. a show-password toggle. */
  trailing?: ReactNode;
}

export function TextField({ label, error, hint, inputRef, trailing, className, ...inputProps }: TextFieldProps) {
  const id = useId();
  const hintId = `${id}-hint`;
  const errorId = `${id}-error`;
  const describedBy = [hint ? hintId : null, error ? errorId : null].filter(Boolean).join(' ') || undefined;
  return (
    <div className={className}>
      <label htmlFor={id} className="block text-sm font-medium text-slate-700 dark:text-slate-200">{label}</label>
      <div className="relative mt-2">
        <input
          {...inputProps}
          id={id}
          ref={inputRef}
          aria-invalid={error ? true : undefined}
          aria-describedby={describedBy}
          className={clsx(inputClass, error ? invalidInputClass : validInputClass, trailing && 'pr-24')}
        />
        {trailing && <div className="absolute inset-y-0 right-2 flex items-center">{trailing}</div>}
      </div>
      {hint && <div id={hintId} className="mt-2 text-xs leading-5 text-slate-500 dark:text-slate-400">{hint}</div>}
      {error && <p id={errorId} className="mt-2 text-sm text-rose-600 dark:text-rose-400">{error}</p>}
    </div>
  );
}

export function PasswordField(props: Omit<TextFieldProps, 'type' | 'trailing'>) {
  const [visible, setVisible] = useState(false);
  return (
    <TextField
      {...props}
      type={visible ? 'text' : 'password'}
      autoCapitalize="none"
      spellCheck={false}
      trailing={(
        <button
          type="button"
          className="rounded-lg px-3 py-1.5 text-xs font-semibold text-slate-600 transition hover:bg-slate-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 dark:text-slate-300 dark:hover:bg-slate-800"
          aria-pressed={visible}
          onClick={() => setVisible((value) => !value)}
        >
          {visible ? 'Ẩn' : 'Hiện'}<span className="sr-only"> mật khẩu</span>
        </button>
      )}
    />
  );
}

type AlertTone = 'error' | 'warning' | 'success' | 'info';

const toneClass: Record<AlertTone, string> = {
  error: 'border-rose-200 bg-rose-50 text-rose-800 dark:border-rose-500/30 dark:bg-rose-500/10 dark:text-rose-200',
  warning: 'border-amber-200 bg-amber-50 text-amber-800 dark:border-amber-500/30 dark:bg-amber-500/10 dark:text-amber-200',
  success: 'border-emerald-200 bg-emerald-50 text-emerald-800 dark:border-emerald-500/30 dark:bg-emerald-500/10 dark:text-emerald-200',
  info: 'border-indigo-200 bg-indigo-50 text-indigo-800 dark:border-indigo-500/30 dark:bg-indigo-500/10 dark:text-indigo-200',
};

export function FormAlert({ tone, children, className }: PropsWithChildren<{ tone: AlertTone; className?: string }>) {
  return (
    <div className={clsx('rounded-xl border px-4 py-3 text-sm leading-6', toneClass[tone], className)} role={tone === 'error' ? 'alert' : 'status'}>
      {children}
    </div>
  );
}

interface SubmitButtonProps {
  busy: boolean;
  busyLabel: string;
  disabled?: boolean;
  children: ReactNode;
}

export function SubmitButton({ busy, busyLabel, disabled, children }: SubmitButtonProps) {
  return (
    <button
      className="w-full rounded-xl bg-indigo-600 px-4 py-3 font-semibold text-white shadow-lg shadow-indigo-600/20 transition hover:bg-indigo-700 focus:outline-none focus-visible:ring-4 focus-visible:ring-indigo-300 disabled:cursor-not-allowed disabled:opacity-60 dark:shadow-none dark:focus-visible:ring-indigo-500/40"
      type="submit"
      disabled={busy || disabled}
      aria-busy={busy || undefined}
    >
      {busy ? busyLabel : children}
    </button>
  );
}

export function SecondaryButton({ children, ...props }: PropsWithChildren<ButtonHTMLAttributes<HTMLButtonElement>>) {
  return (
    <button
      type="button"
      {...props}
      className={clsx('rounded-xl border border-slate-300 px-4 py-2.5 text-sm font-semibold text-slate-700 transition hover:bg-slate-50 focus:outline-none focus-visible:ring-4 focus-visible:ring-indigo-200 disabled:cursor-not-allowed disabled:opacity-60 dark:border-slate-700 dark:text-slate-200 dark:hover:bg-slate-800 dark:focus-visible:ring-indigo-500/30', props.className)}
    >
      {children}
    </button>
  );
}
