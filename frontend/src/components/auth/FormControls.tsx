import { useId, useState } from 'react';
import type { ButtonHTMLAttributes, InputHTMLAttributes, PropsWithChildren, ReactNode, Ref } from 'react';
import clsx from 'clsx';
import {
  AlertCircleIcon,
  AlertTriangleIcon,
  CheckCircleIcon,
  EyeIcon,
  EyeOffIcon,
  InfoIcon,
} from '../common/Icons';
import { LoadingSpinner } from '../common/UiPrimitives';

const inputClass =
  'min-h-11 w-full rounded-xl border bg-white px-3.5 py-2.5 text-base text-slate-900 shadow-2xs outline-none transition placeholder:text-slate-400 focus:ring-4 disabled:cursor-not-allowed disabled:bg-slate-50 disabled:opacity-65 sm:text-sm dark:bg-slate-950 dark:text-slate-100 dark:placeholder:text-slate-500 dark:disabled:bg-slate-900';
const validInputClass =
  'border-slate-300/90 hover:border-slate-400/90 focus:border-indigo-500 focus:ring-indigo-500/15 dark:border-slate-700 dark:hover:border-slate-600 dark:focus:border-indigo-400 dark:focus:ring-indigo-500/25';
const invalidInputClass =
  'border-rose-400 bg-rose-50/30 focus:border-rose-500 focus:ring-rose-500/15 dark:border-rose-500/70 dark:bg-rose-950/20 dark:focus:ring-rose-500/25';

export const linkClass =
  'font-semibold text-indigo-600 underline-offset-4 transition hover:text-indigo-700 hover:underline focus-visible:rounded focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:ring-offset-1 dark:text-indigo-400 dark:hover:text-indigo-300';

export const primaryLinkClass =
  'inline-flex min-h-11 w-full items-center justify-center gap-2 rounded-xl bg-indigo-600 px-4 py-2.5 text-center text-sm font-semibold text-white shadow-sm shadow-indigo-600/25 transition hover:bg-indigo-700 active:scale-[0.99] focus:outline-none focus-visible:ring-4 focus-visible:ring-indigo-500/30 disabled:cursor-not-allowed disabled:opacity-60 motion-reduce:active:scale-100 dark:shadow-none dark:focus-visible:ring-indigo-500/40';

export const secondaryLinkClass =
  'inline-flex min-h-11 w-full items-center justify-center gap-2 rounded-xl border border-slate-300 bg-white px-4 py-2.5 text-center text-sm font-semibold text-slate-700 shadow-2xs transition hover:border-slate-400 hover:bg-slate-50 focus:outline-none focus-visible:ring-4 focus-visible:ring-indigo-500/20 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-200 dark:hover:bg-slate-800 dark:focus-visible:ring-indigo-500/30';

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
    <div className={clsx('min-w-0', className)}>
      <label htmlFor={id} className="block text-sm font-semibold text-slate-800 dark:text-slate-200">
        {label}
      </label>
      <div className="relative mt-1.5">
        <input
          {...inputProps}
          id={id}
          ref={inputRef}
          aria-invalid={error ? true : undefined}
          aria-describedby={describedBy}
          className={clsx(inputClass, error ? invalidInputClass : validInputClass, trailing && 'pr-24')}
        />
        {trailing && <div className="absolute inset-y-0 right-1.5 flex items-center">{trailing}</div>}
      </div>
      {error && (
        <p id={errorId} className="mt-1.5 flex items-start gap-1.5 text-xs font-medium leading-5 text-rose-600 dark:text-rose-400">
          <AlertCircleIcon className="mt-0.5 size-3.5 shrink-0" />
          <span>{error}</span>
        </p>
      )}
      {hint && (
        <div id={hintId} className="mt-2 text-xs leading-5 text-slate-600 dark:text-slate-400">
          {hint}
        </div>
      )}
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
          className="inline-flex min-h-9 items-center gap-1 rounded-lg px-2.5 py-1.5 text-xs font-semibold text-slate-600 transition hover:bg-slate-100 hover:text-slate-900 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 dark:text-slate-300 dark:hover:bg-slate-800 dark:hover:text-white"
          aria-pressed={visible}
          onClick={() => setVisible((value) => !value)}
        >
          {visible ? <EyeOffIcon className="size-3.5" /> : <EyeIcon className="size-3.5" />}
          <span>{visible ? 'Ẩn' : 'Hiện'}</span>
          <span className="sr-only"> mật khẩu</span>
        </button>
      )}
    />
  );
}

type AlertTone = 'error' | 'warning' | 'success' | 'info';

const toneClass: Record<AlertTone, string> = {
  error: 'border-rose-200/90 bg-rose-50/90 text-rose-900 dark:border-rose-500/30 dark:bg-rose-500/10 dark:text-rose-200',
  warning: 'border-amber-200/90 bg-amber-50/90 text-amber-900 dark:border-amber-500/30 dark:bg-amber-500/10 dark:text-amber-200',
  success: 'border-emerald-200/90 bg-emerald-50/90 text-emerald-900 dark:border-emerald-500/30 dark:bg-emerald-500/10 dark:text-emerald-200',
  info: 'border-indigo-200/90 bg-indigo-50/90 text-indigo-900 dark:border-indigo-500/30 dark:bg-indigo-500/10 dark:text-indigo-200',
};

const iconToneClass: Record<AlertTone, string> = {
  error: 'text-rose-600 dark:text-rose-400',
  warning: 'text-amber-600 dark:text-amber-400',
  success: 'text-emerald-600 dark:text-emerald-400',
  info: 'text-indigo-600 dark:text-indigo-400',
};

function AlertIcon({ tone }: { tone: AlertTone }) {
  const cls = clsx('mt-0.5 size-4 shrink-0', iconToneClass[tone]);
  if (tone === 'error') return <AlertCircleIcon className={cls} />;
  if (tone === 'warning') return <AlertTriangleIcon className={cls} />;
  if (tone === 'success') return <CheckCircleIcon className={cls} />;
  return <InfoIcon className={cls} />;
}

export function FormAlert({ tone, children, className }: PropsWithChildren<{ tone: AlertTone; className?: string }>) {
  return (
    <div
      className={clsx('flex items-start gap-2.5 rounded-xl border px-3.5 py-3 text-sm leading-6', toneClass[tone], className)}
      role={tone === 'error' ? 'alert' : 'status'}
    >
      <AlertIcon tone={tone} />
      <div className="min-w-0 flex-1">{children}</div>
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
      className={primaryLinkClass}
      type="submit"
      disabled={busy || disabled}
      aria-busy={busy || undefined}
    >
      {busy && <LoadingSpinner className="size-4 text-white" />}
      <span>{busy ? busyLabel : children}</span>
    </button>
  );
}

export function SecondaryButton({ children, className, ...props }: PropsWithChildren<ButtonHTMLAttributes<HTMLButtonElement>>) {
  return (
    <button
      type="button"
      {...props}
      className={clsx(
        'inline-flex min-h-11 items-center justify-center gap-2 rounded-xl border border-slate-300 bg-white px-4 py-2.5 text-sm font-semibold text-slate-700 shadow-2xs transition hover:border-slate-400 hover:bg-slate-50 focus:outline-none focus-visible:ring-4 focus-visible:ring-indigo-500/20 disabled:cursor-not-allowed disabled:opacity-60 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-200 dark:hover:bg-slate-800 dark:focus-visible:ring-indigo-500/30',
        className,
      )}
    >
      {children}
    </button>
  );
}
