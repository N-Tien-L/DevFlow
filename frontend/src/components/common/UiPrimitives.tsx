import type { ReactNode } from 'react';
import clsx from 'clsx';
import { workspaceRoleLabel } from '../../auth/workspaceRole';
import type { ColumnStatusCategory, TaskPriority } from '../../types/board';
import { PrioritySignalIcon } from './Icons';

export type BadgeTone = 'neutral' | 'brand' | 'info' | 'violet' | 'success' | 'warning' | 'danger';

const BADGE_TONES: Record<BadgeTone, string> = {
  neutral: 'bg-slate-100 text-slate-700 ring-slate-200/80',
  brand: 'bg-indigo-50 text-indigo-700 ring-indigo-200/80',
  info: 'bg-sky-50 text-sky-700 ring-sky-200/80',
  violet: 'bg-violet-50 text-violet-700 ring-violet-200/80',
  success: 'bg-emerald-50 text-emerald-700 ring-emerald-200/80',
  warning: 'bg-amber-50 text-amber-800 ring-amber-200/90',
  danger: 'bg-rose-50 text-rose-700 ring-rose-200/90',
};

export function Badge({
  tone = 'neutral',
  children,
  className,
}: {
  tone?: BadgeTone;
  children: ReactNode;
  className?: string;
}) {
  return (
    <span
      className={clsx(
        'inline-flex shrink-0 items-center gap-1.5 whitespace-nowrap rounded-full px-2.5 py-0.5 text-[11px] font-semibold leading-4 ring-1 ring-inset',
        BADGE_TONES[tone],
        className,
      )}
    >
      {children}
    </span>
  );
}

export const PRIORITY_LABEL: Record<TaskPriority, string> = {
  LOW: 'Thấp',
  MEDIUM: 'Trung bình',
  HIGH: 'Cao',
  URGENT: 'Khẩn cấp',
};

const PRIORITY_STYLE: Record<TaskPriority, string> = {
  LOW: 'bg-slate-100 text-slate-700 ring-slate-200/90',
  MEDIUM: 'bg-sky-50 text-sky-700 ring-sky-200/90',
  HIGH: 'bg-amber-50 text-amber-800 ring-amber-200/90',
  URGENT: 'bg-rose-50 text-rose-700 ring-rose-200/90',
};

export function PriorityBadge({ priority, className }: { priority: TaskPriority; className?: string }) {
  return (
    <span
      className={clsx(
        'inline-flex items-center gap-1 rounded-md px-2 py-0.5 text-[11px] font-semibold leading-4 ring-1 ring-inset',
        PRIORITY_STYLE[priority],
        className,
      )}
    >
      <PrioritySignalIcon priority={priority} />
      <span>{PRIORITY_LABEL[priority]}</span>
    </span>
  );
}

export const STATUS_CATEGORY_META: Record<
  ColumnStatusCategory,
  { label: string; tone: BadgeTone; dot: string; bar: string }
> = {
  TODO: { label: 'Cần làm', tone: 'neutral', dot: 'bg-slate-400', bar: 'bg-slate-400/80' },
  IN_PROGRESS: { label: 'Đang làm', tone: 'info', dot: 'bg-sky-500', bar: 'bg-sky-500' },
  IN_REVIEW: { label: 'Chờ duyệt', tone: 'violet', dot: 'bg-violet-500', bar: 'bg-violet-500' },
  DONE: { label: 'Hoàn thành', tone: 'success', dot: 'bg-emerald-500', bar: 'bg-emerald-500' },
};

export function StatusCategoryBadge({ category, className }: { category: ColumnStatusCategory; className?: string }) {
  const meta = STATUS_CATEGORY_META[category] ?? STATUS_CATEGORY_META.TODO;
  return (
    <Badge tone={meta.tone} className={className}>
      <span className={clsx('size-1.5 rounded-full', meta.dot)} aria-hidden="true" />
      <span>{meta.label}</span>
    </Badge>
  );
}

export function RoleBadge({ role, className }: { role: string; className?: string }) {
  const tone: BadgeTone = role === 'OWNER' ? 'brand' : role === 'ADMIN' ? 'violet' : 'neutral';
  return (
    <Badge tone={tone} className={className}>
      {workspaceRoleLabel(role)}
    </Badge>
  );
}

const AVATAR_PALETTES = [
  'bg-indigo-100 text-indigo-700 ring-indigo-200/80',
  'bg-sky-100 text-sky-700 ring-sky-200/80',
  'bg-violet-100 text-violet-700 ring-violet-200/80',
  'bg-emerald-100 text-emerald-700 ring-emerald-200/80',
  'bg-amber-100 text-amber-800 ring-amber-200/80',
  'bg-rose-100 text-rose-700 ring-rose-200/80',
  'bg-teal-100 text-teal-700 ring-teal-200/80',
];

function hashString(seed: string) {
  let hash = 0;
  for (let i = 0; i < seed.length; i += 1) {
    hash = (hash * 31 + seed.charCodeAt(i)) >>> 0;
  }
  return hash;
}

export function initialsFrom(name: string) {
  const clean = name.trim();
  if (!clean) return '?';
  const parts = clean.split(/\s+/).filter(Boolean);
  if (parts.length >= 2) {
    return `${parts[0].charAt(0)}${parts[parts.length - 1].charAt(0)}`.toUpperCase();
  }
  return clean.slice(0, 2).toUpperCase();
}

export function Avatar({
  seed,
  name,
  avatarUrl,
  size = 'sm',
  className,
}: {
  seed: string;
  name: string;
  avatarUrl?: string | null;
  size?: 'xs' | 'sm' | 'md' | 'lg';
  className?: string;
}) {
  const sizeClass =
    size === 'xs'
      ? 'size-5 text-[10px]'
      : size === 'sm'
        ? 'size-6 text-[10px]'
        : size === 'lg'
          ? 'size-11 text-sm'
          : 'size-8 text-xs';

  if (avatarUrl) {
    return (
      <img
        src={avatarUrl}
        alt=""
        aria-hidden="true"
        className={clsx('shrink-0 rounded-full object-cover ring-1 ring-slate-200', sizeClass, className)}
      />
    );
  }

  const palette = AVATAR_PALETTES[hashString(seed || name) % AVATAR_PALETTES.length];
  return (
    <span
      className={clsx(
        'inline-grid shrink-0 place-items-center rounded-full font-bold tracking-tight ring-1 ring-inset',
        sizeClass,
        palette,
        className,
      )}
      aria-hidden="true"
    >
      {initialsFrom(name)}
    </span>
  );
}

export function LoadingSpinner({ className }: { className?: string }) {
  return (
    <svg
      className={clsx('size-4 animate-spin motion-reduce:animate-none', className)}
      viewBox="0 0 24 24"
      fill="none"
      aria-hidden="true"
    >
      <circle className="opacity-25" cx="12" cy="12" r="9.5" stroke="currentColor" strokeWidth="3" />
      <path className="opacity-85" fill="currentColor" d="M4 12a8 8 0 0 1 8-8V1.5C6.2 1.5 1.5 6.2 1.5 12H4Z" />
    </svg>
  );
}

export function EmptyStatePanel({
  icon,
  eyebrow,
  title,
  description,
  actions,
  className,
}: {
  icon: ReactNode;
  eyebrow?: string;
  title: string;
  description: ReactNode;
  actions?: ReactNode;
  className?: string;
}) {
  return (
    <section
      className={clsx(
        'rounded-2xl border border-dashed border-slate-300/90 bg-white/95 px-5 py-10 text-center shadow-xs sm:px-8 sm:py-12',
        className,
      )}
    >
      <div className="mx-auto mb-4 grid size-12 place-items-center rounded-2xl bg-indigo-50 text-indigo-600 ring-1 ring-inset ring-indigo-100">
        {icon}
      </div>
      {eyebrow && (
        <p className="text-[11px] font-bold uppercase tracking-[0.16em] text-indigo-600">{eyebrow}</p>
      )}
      <h2 className="mt-1.5 text-lg font-bold tracking-tight text-slate-950 sm:text-xl">{title}</h2>
      <p className="mx-auto mt-2 max-w-md text-sm leading-6 text-slate-600">{description}</p>
      {actions && <div className="mt-6 flex flex-wrap items-center justify-center gap-3">{actions}</div>}
    </section>
  );
}
