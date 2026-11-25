import React from 'react';
import type { NotificationResponse } from '../../types/notification';
import { formatRelativeTime } from '../../utils/formatRelativeTime';

interface NotificationItemProps {
  notification: NotificationResponse;
  onMarkRead: (id: string) => void;
  onSelect?: (notification: NotificationResponse) => void;
}

function getNotificationIcon(type: string) {
  switch (type) {
    case 'ci.failure_summarized':
      return {
        bg: 'bg-rose-100/90 text-rose-600 ring-rose-200/80',
        icon: (
          <svg className="size-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2} aria-hidden="true">
            <path strokeLinecap="round" strokeLinejoin="round" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
          </svg>
        ),
      };
    case 'risk.deadline_flagged':
      return {
        bg: 'bg-amber-100/90 text-amber-700 ring-amber-200/80',
        icon: (
          <svg className="size-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2} aria-hidden="true">
            <path strokeLinecap="round" strokeLinejoin="round" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" />
          </svg>
        ),
      };
    case 'task.created':
      return {
        bg: 'bg-sky-100/90 text-sky-700 ring-sky-200/80',
        icon: (
          <svg className="size-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2} aria-hidden="true">
            <path strokeLinecap="round" strokeLinejoin="round" d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2m-6 9l2 2 4-4" />
          </svg>
        ),
      };
    case 'task.status_changed':
      return {
        bg: 'bg-violet-100/90 text-violet-700 ring-violet-200/80',
        icon: (
          <svg className="size-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2} aria-hidden="true">
            <path strokeLinecap="round" strokeLinejoin="round" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
          </svg>
        ),
      };
    case 'git.pr_opened':
    case 'git.pr_merged':
      return {
        bg: 'bg-emerald-100/90 text-emerald-700 ring-emerald-200/80',
        icon: (
          <svg className="size-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2} aria-hidden="true">
            <path strokeLinecap="round" strokeLinejoin="round" d="M7 16V4m0 0a3 3 0 10-3 3 3 3 0 003-3zm10 8v-4m0 0a3 3 0 10-3-3 3 3 0 003 3zm0 0a5 5 0 01-5 5H7" />
          </svg>
        ),
      };
    default:
      return {
        bg: 'bg-slate-100 text-slate-600 ring-slate-200/80',
        icon: (
          <svg className="size-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2} aria-hidden="true">
            <path strokeLinecap="round" strokeLinejoin="round" d="M15 17h5l-1.405-1.405A2.032 2.032 0 0118 14.158V11a6.002 6.002 0 00-4-5.659V5a2 2 0 10-4 0v.341C7.67 6.165 6 8.388 6 11v3.159c0 .538-.214 1.055-.595 1.436L4 17h5m6 0v1a3 3 0 11-6 0v-1m6 0H9" />
          </svg>
        ),
      };
  }
}

export function NotificationItem({
  notification,
  onMarkRead,
  onSelect,
}: NotificationItemProps) {
  const { bg, icon } = getNotificationIcon(notification.type);
  const relativeTime = formatRelativeTime(notification.createdAt);

  const handleClick = () => {
    if (!notification.isRead) {
      onMarkRead(notification.id);
    }
    if (onSelect) {
      onSelect(notification);
    }
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      handleClick();
    }
  };

  return (
    <div
      role="button"
      tabIndex={0}
      onClick={handleClick}
      onKeyDown={handleKeyDown}
      className={`group relative flex cursor-pointer items-start gap-3 p-3.5 text-left transition focus:outline-none focus-visible:bg-indigo-50/70 ${
        notification.isRead
          ? 'bg-white text-slate-600 hover:bg-slate-50'
          : 'bg-indigo-50/45 font-medium text-slate-900 hover:bg-indigo-50/80'
      }`}
      aria-label={`${notification.isRead ? 'Đã đọc' : 'Chưa đọc'}: ${notification.title}`}
    >
      <div className={`mt-0.5 grid size-8 shrink-0 place-items-center rounded-xl ring-1 ring-inset ${bg}`}>
        {icon}
      </div>

      <div className="min-w-0 flex-1">
        <div className="flex items-center justify-between gap-2">
          <p className="truncate text-xs font-semibold text-slate-900">
            {notification.title}
          </p>
          <span className="tabular-nums shrink-0 text-[11px] text-slate-500">
            {relativeTime}
          </span>
        </div>
        <p className="mt-0.5 line-clamp-2 text-xs leading-4 text-slate-600">
          {notification.content}
        </p>
      </div>

      {!notification.isRead && (
        <span
          className="mt-2 size-2 shrink-0 rounded-full bg-indigo-600"
          aria-hidden="true"
        />
      )}
    </div>
  );
}
