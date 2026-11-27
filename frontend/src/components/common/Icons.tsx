import type { SVGProps } from 'react';
import clsx from 'clsx';

type IconProps = SVGProps<SVGSVGElement>;

export function BrandMark({ size = 'md', className }: { size?: 'sm' | 'md' | 'lg'; className?: string }) {
  const box = size === 'sm' ? 'size-8 rounded-lg' : size === 'lg' ? 'size-11 rounded-2xl' : 'size-9 rounded-xl';
  const glyph = size === 'sm' ? 'size-4' : size === 'lg' ? 'size-6' : 'size-5';
  return (
    <span
      className={clsx(
        'relative inline-grid shrink-0 place-items-center bg-gradient-to-br from-indigo-600 via-indigo-600 to-violet-600 text-white shadow-sm shadow-indigo-600/30 ring-1 ring-inset ring-white/25',
        box,
        className,
      )}
      aria-hidden="true"
    >
      <svg className={glyph} viewBox="0 0 24 24" fill="none">
        <path
          d="M5 6.5h6.5a4.5 4.5 0 0 1 0 9H9.5"
          stroke="currentColor"
          strokeWidth="2.3"
          strokeLinecap="round"
        />
        <path
          d="M5 6.5v11"
          stroke="currentColor"
          strokeWidth="2.3"
          strokeLinecap="round"
        />
        <circle cx="17.5" cy="17.5" r="2.25" fill="#38bdf8" />
        <path
          d="M12.2 15.3 15.6 17"
          stroke="#bae6fd"
          strokeWidth="2"
          strokeLinecap="round"
        />
      </svg>
    </span>
  );
}

export function SearchIcon({ className, ...props }: IconProps) {
  return (
    <svg className={clsx('size-4', className)} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.9} aria-hidden="true" {...props}>
      <circle cx="11" cy="11" r="7" />
      <path strokeLinecap="round" d="m20 20-3.5-3.5" />
    </svg>
  );
}

export function XIcon({ className, ...props }: IconProps) {
  return (
    <svg className={clsx('size-4', className)} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} aria-hidden="true" {...props}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M18 6 6 18M6 6l12 12" />
    </svg>
  );
}

export function GripVerticalIcon({ className, ...props }: IconProps) {
  return (
    <svg className={clsx('size-4', className)} viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" {...props}>
      <circle cx="9" cy="6" r="1.5" />
      <circle cx="15" cy="6" r="1.5" />
      <circle cx="9" cy="12" r="1.5" />
      <circle cx="15" cy="12" r="1.5" />
      <circle cx="9" cy="18" r="1.5" />
      <circle cx="15" cy="18" r="1.5" />
    </svg>
  );
}

export function ChevronDownIcon({ className, ...props }: IconProps) {
  return (
    <svg className={clsx('size-4', className)} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} aria-hidden="true" {...props}>
      <path strokeLinecap="round" strokeLinejoin="round" d="m6 9 6 6 6-6" />
    </svg>
  );
}

export function ArrowRightIcon({ className, ...props }: IconProps) {
  return (
    <svg className={clsx('size-4', className)} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} aria-hidden="true" {...props}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M5 12h14m-6-6 6 6-6 6" />
    </svg>
  );
}

export function ArrowRightLeftIcon({ className, ...props }: IconProps) {
  return (
    <svg className={clsx('size-4', className)} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.85} aria-hidden="true" {...props}>
      <path strokeLinecap="round" strokeLinejoin="round" d="m16 3 4 4-4 4M20 7H4M8 21l-4-4 4-4M4 17h16" />
    </svg>
  );
}

export function CalendarIcon({ className, ...props }: IconProps) {
  return (
    <svg className={clsx('size-3.5', className)} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.9} aria-hidden="true" {...props}>
      <rect x="3" y="4.5" width="18" height="16.5" rx="2.5" />
      <path strokeLinecap="round" d="M16 2.5v4M8 2.5v4M3 9.5h18" />
    </svg>
  );
}

export function ClockIcon({ className, ...props }: IconProps) {
  return (
    <svg className={clsx('size-3.5', className)} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.9} aria-hidden="true" {...props}>
      <circle cx="12" cy="12" r="9" />
      <path strokeLinecap="round" strokeLinejoin="round" d="M12 7v5l3 2" />
    </svg>
  );
}

export function AlertCircleIcon({ className, ...props }: IconProps) {
  return (
    <svg className={clsx('size-4', className)} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} aria-hidden="true" {...props}>
      <circle cx="12" cy="12" r="9.5" />
      <path strokeLinecap="round" d="M12 7.5v5.5" />
      <circle cx="12" cy="16.25" r="0.85" fill="currentColor" stroke="none" />
    </svg>
  );
}

export function AlertTriangleIcon({ className, ...props }: IconProps) {
  return (
    <svg className={clsx('size-4', className)} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.9} aria-hidden="true" {...props}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M10.3 3.9 2.3 18a1.9 1.9 0 0 0 1.7 2.8h16a1.9 1.9 0 0 0 1.7-2.8l-8-14.1a1.9 1.9 0 0 0-3.4 0Z" />
      <path strokeLinecap="round" d="M12 9.5v4.5" />
      <circle cx="12" cy="17.1" r="0.85" fill="currentColor" stroke="none" />
    </svg>
  );
}

export function CheckCircleIcon({ className, ...props }: IconProps) {
  return (
    <svg className={clsx('size-4', className)} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} aria-hidden="true" {...props}>
      <circle cx="12" cy="12" r="9.5" />
      <path strokeLinecap="round" strokeLinejoin="round" d="m8.5 12.2 2.4 2.4 4.8-5" />
    </svg>
  );
}

export function InfoIcon({ className, ...props }: IconProps) {
  return (
    <svg className={clsx('size-4', className)} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} aria-hidden="true" {...props}>
      <circle cx="12" cy="12" r="9.5" />
      <path strokeLinecap="round" d="M12 11v5.5" />
      <circle cx="12" cy="7.75" r="0.85" fill="currentColor" stroke="none" />
    </svg>
  );
}

export function RefreshIcon({ className, ...props }: IconProps) {
  return (
    <svg className={clsx('size-4', className)} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.9} aria-hidden="true" {...props}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M20 12a8 8 0 1 1-2.34-5.66L20 8.5M20 4v4.5h-4.5" />
    </svg>
  );
}

export function SparklesIcon({ className, ...props }: IconProps) {
  return (
    <svg className={clsx('size-4', className)} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.85} aria-hidden="true" {...props}>
      <path strokeLinecap="round" strokeLinejoin="round" d="m12 3 1.7 4.7L18.5 9.5l-4.8 1.8L12 16l-1.7-4.7-4.8-1.8 4.8-1.8L12 3Z" />
      <path strokeLinecap="round" strokeLinejoin="round" d="m19 15 .8 2.2L22 18l-2.2.8L19 21l-.8-2.2L16 18l2.2-.8L19 15Z" />
    </svg>
  );
}

export function EyeIcon({ className, ...props }: IconProps) {
  return (
    <svg className={clsx('size-4', className)} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.9} aria-hidden="true" {...props}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M2.5 12s3.5-6.5 9.5-6.5 9.5 6.5 9.5 6.5-3.5 6.5-9.5 6.5S2.5 12 2.5 12Z" />
      <circle cx="12" cy="12" r="2.75" />
    </svg>
  );
}

export function EyeOffIcon({ className, ...props }: IconProps) {
  return (
    <svg className={clsx('size-4', className)} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.9} aria-hidden="true" {...props}>
      <path strokeLinecap="round" strokeLinejoin="round" d="m3 3 18 18M10.6 10.6a2.75 2.75 0 0 0 3.8 3.8M9.9 5.2A9.9 9.9 0 0 1 12 5c6 0 9.5 7 9.5 7a16.8 16.8 0 0 1-3.1 4.1M6.4 6.4C4 8.2 2.5 12 2.5 12s3.5 7 9.5 7c1.4 0 2.7-.3 3.9-.9" />
    </svg>
  );
}

export function PlusIcon({ className, ...props }: IconProps) {
  return (
    <svg className={clsx('size-4', className)} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.1} aria-hidden="true" {...props}>
      <path strokeLinecap="round" d="M12 5v14M5 12h14" />
    </svg>
  );
}

export function LogOutIcon({ className, ...props }: IconProps) {
  return (
    <svg className={clsx('size-4', className)} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.9} aria-hidden="true" {...props}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9" />
    </svg>
  );
}

export function LayoutGridIcon({ className, ...props }: IconProps) {
  return (
    <svg className={clsx('size-4', className)} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.85} aria-hidden="true" {...props}>
      <rect x="3" y="3" width="7.5" height="7.5" rx="1.5" />
      <rect x="13.5" y="3" width="7.5" height="7.5" rx="1.5" />
      <rect x="3" y="13.5" width="7.5" height="7.5" rx="1.5" />
      <rect x="13.5" y="13.5" width="7.5" height="7.5" rx="1.5" />
    </svg>
  );
}

export function KanbanIcon({ className, ...props }: IconProps) {
  return (
    <svg className={clsx('size-4', className)} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.85} aria-hidden="true" {...props}>
      <rect x="3" y="3" width="18" height="18" rx="2.5" />
      <path strokeLinecap="round" d="M8.5 7.5v9M12 7.5v5.5M15.5 7.5v7.5" />
    </svg>
  );
}

export function ArchiveIcon({ className, ...props }: IconProps) {
  return (
    <svg className={clsx('size-3.5', className)} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.9} aria-hidden="true" {...props}>
      <rect x="3" y="4" width="18" height="4" rx="1" />
      <path strokeLinecap="round" strokeLinejoin="round" d="M5 8v10a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8M10 12h4" />
    </svg>
  );
}

export function FilterIcon({ className, ...props }: IconProps) {
  return (
    <svg className={clsx('size-4', className)} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.85} aria-hidden="true" {...props}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M3 5h18l-7 8.5V19l-4-2v-3.5L3 5Z" />
    </svg>
  );
}

export function LockIcon({ className, ...props }: IconProps) {
  return (
    <svg className={clsx('size-3.5', className)} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.9} aria-hidden="true" {...props}>
      <rect x="4" y="11" width="16" height="10" rx="2" />
      <path strokeLinecap="round" d="M8 11V7.5a4 4 0 1 1 8 0V11" />
    </svg>
  );
}

export function MessageSquareIcon({ className, ...props }: IconProps) {
  return (
    <svg className={clsx('size-4', className)} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.85} aria-hidden="true" {...props}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M21 15a2 2 0 0 1-2 2H8l-5 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v10Z" />
    </svg>
  );
}

export function SendIcon({ className, ...props }: IconProps) {
  return (
    <svg className={clsx('size-4', className)} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.9} aria-hidden="true" {...props}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M22 2 11 13M22 2l-7 20-4-9-9-4 20-7Z" />
    </svg>
  );
}

export function UserIcon({ className, ...props }: IconProps) {
  return (
    <svg className={clsx('size-3.5', className)} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.9} aria-hidden="true" {...props}>
      <circle cx="12" cy="8" r="4" />
      <path strokeLinecap="round" d="M4.5 20a7.5 7.5 0 0 1 15 0" />
    </svg>
  );
}

export function WifiOffIcon({ className, ...props }: IconProps) {
  return (
    <svg className={clsx('size-3.5', className)} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.9} aria-hidden="true" {...props}>
      <path strokeLinecap="round" strokeLinejoin="round" d="m2 2 20 20M8.5 16.5a5 5 0 0 1 7 0M5 12.8a10 10 0 0 1 5.2-2.5M14.6 10.6a10 10 0 0 1 4.4 2.2M12 20h.01" />
    </svg>
  );
}

export function EditPencilIcon({ className, ...props }: IconProps) {
  return (
    <svg className={clsx('size-3.5', className)} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.9} aria-hidden="true" {...props}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5Z" />
    </svg>
  );
}

export function GitBranchIcon({ className, ...props }: IconProps) {
  return (
    <svg className={clsx('size-3.5', className)} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.9} aria-hidden="true" {...props}>
      <circle cx="6" cy="6" r="2.5" />
      <circle cx="18" cy="6" r="2.5" />
      <circle cx="6" cy="18" r="2.5" />
      <path strokeLinecap="round" d="M6 8.5v7M18 8.5c0 4-3 5-12 5" />
    </svg>
  );
}

export function GitCommitIcon({ className, ...props }: IconProps) {
  return (
    <svg className={clsx('size-3.5', className)} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} aria-hidden="true" {...props}>
      <circle cx="12" cy="12" r="3.5" />
      <path strokeLinecap="round" d="M3 12h5.5M15.5 12H21" />
    </svg>
  );
}

export function GitPullRequestIcon({ className, ...props }: IconProps) {
  return (
    <svg className={clsx('size-3.5', className)} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.95} aria-hidden="true" {...props}>
      <circle cx="6" cy="6" r="2.5" />
      <circle cx="6" cy="18" r="2.5" />
      <circle cx="18" cy="18" r="2.5" />
      <path strokeLinecap="round" strokeLinejoin="round" d="M6 8.5v7M13 6h2.5A2.5 2.5 0 0 1 18 8.5v7M15 4l-2 2 2 2" />
    </svg>
  );
}

export function GitMergeIcon({ className, ...props }: IconProps) {
  return (
    <svg className={clsx('size-3.5', className)} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.95} aria-hidden="true" {...props}>
      <circle cx="6" cy="6" r="2.5" />
      <circle cx="6" cy="18" r="2.5" />
      <circle cx="18" cy="18" r="2.5" />
      <path strokeLinecap="round" strokeLinejoin="round" d="M6 8.5v7M6 8.5c0 5 4.5 7 9.5 7" />
    </svg>
  );
}

export function ExternalLinkIcon({ className, ...props }: IconProps) {
  return (
    <svg className={clsx('size-3', className)} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} aria-hidden="true" {...props}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M14 5h5v5M10 14 19 5M19 14v4a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 5 18V6.5A1.5 1.5 0 0 1 6.5 5H10" />
    </svg>
  );
}

export function ShieldCheckIcon({ className, ...props }: IconProps) {
  return (
    <svg className={clsx('size-4', className)} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.9} aria-hidden="true" {...props}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M12 3 4.5 6v5.5c0 4.6 3.2 8.7 7.5 9.8 4.3-1.1 7.5-5.2 7.5-9.8V6L12 3Z" />
      <path strokeLinecap="round" strokeLinejoin="round" d="m9 12.2 2 2 4-4.2" />
    </svg>
  );
}

export function PrioritySignalIcon({ priority, className }: { priority: 'LOW' | 'MEDIUM' | 'HIGH' | 'URGENT'; className?: string }) {
  if (priority === 'URGENT') {
    return (
      <svg className={clsx('size-3.5 shrink-0', className)} viewBox="0 0 16 16" fill="currentColor" aria-hidden="true">
        <path d="M8 1.5a6.5 6.5 0 1 0 0 13 6.5 6.5 0 0 0 0-13Zm-.75 3.25a.75.75 0 0 1 1.5 0v4a.75.75 0 0 1-1.5 0v-4Zm.75 7.5a.9.9 0 1 1 0-1.8.9.9 0 0 1 0 1.8Z" />
      </svg>
    );
  }
  if (priority === 'HIGH') {
    return (
      <svg className={clsx('size-3.5 shrink-0', className)} viewBox="0 0 16 16" fill="currentColor" aria-hidden="true">
        <rect x="2" y="10" width="2.5" height="4" rx="0.8" />
        <rect x="6.75" y="6.5" width="2.5" height="7.5" rx="0.8" />
        <rect x="11.5" y="2.5" width="2.5" height="11.5" rx="0.8" />
      </svg>
    );
  }
  if (priority === 'MEDIUM') {
    return (
      <svg className={clsx('size-3.5 shrink-0', className)} viewBox="0 0 16 16" fill="currentColor" aria-hidden="true">
        <rect x="2" y="10" width="2.5" height="4" rx="0.8" />
        <rect x="6.75" y="6.5" width="2.5" height="7.5" rx="0.8" />
        <rect x="11.5" y="2.5" width="2.5" height="11.5" rx="0.8" opacity="0.28" />
      </svg>
    );
  }
  return (
    <svg className={clsx('size-3.5 shrink-0', className)} viewBox="0 0 16 16" fill="currentColor" aria-hidden="true">
      <rect x="2" y="10" width="2.5" height="4" rx="0.8" />
      <rect x="6.75" y="6.5" width="2.5" height="7.5" rx="0.8" opacity="0.28" />
      <rect x="11.5" y="2.5" width="2.5" height="11.5" rx="0.8" opacity="0.28" />
    </svg>
  );
}
