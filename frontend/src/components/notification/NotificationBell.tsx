import { useRef } from 'react';
import { useAuth } from '../../auth/AuthProvider';
import { useNotificationStore } from '../../stores/notificationStore';
import type { NotificationResponse } from '../../types/notification';
import { NotificationDropdown } from './NotificationDropdown';

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

interface NotificationBellProps {
  onSelectTask?: (taskId: string) => void;
}

export function NotificationBell({ onSelectTask }: NotificationBellProps) {
  const { request } = useAuth();
  const buttonRef = useRef<HTMLButtonElement>(null);
  const {
    unreadCount,
    isDropdownOpen,
    toggleDropdown,
    closeDropdown,
  } = useNotificationStore();

  const handleSelectNotification = (notification: NotificationResponse) => {
    if (notification.referenceId && UUID_PATTERN.test(notification.referenceId) && onSelectTask) {
      onSelectTask(notification.referenceId);
      closeDropdown();
    }
  };

  const badgeText = unreadCount > 99 ? '99+' : String(unreadCount);

  return (
    <div className="relative inline-block text-left">
      <button
        ref={buttonRef}
        type="button"
        id="notification-bell-button"
        onClick={toggleDropdown}
        aria-expanded={isDropdownOpen}
        aria-haspopup="true"
        aria-label={
          unreadCount > 0
            ? `Thông báo (${unreadCount} chưa đọc)`
            : 'Thông báo'
        }
        className="relative grid size-10 place-items-center rounded-xl text-slate-600 transition hover:bg-slate-100 hover:text-slate-900 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:ring-offset-1"
      >
        <svg
          className="size-5"
          fill="none"
          viewBox="0 0 24 24"
          stroke="currentColor"
          strokeWidth={1.85}
          aria-hidden="true"
        >
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            d="M14.857 17.082a23.848 23.848 0 005.454-1.31A8.967 8.967 0 0118 9.75v-.7V9A6 6 0 006 9v.75a8.967 8.967 0 01-2.312 6.022c1.733.64 3.56 1.085 5.455 1.31m5.714 0a24.255 24.255 0 01-5.714 0m5.714 0a3 3 0 11-5.714 0"
          />
        </svg>

        {unreadCount > 0 && (
          <span
            className="tabular-nums absolute right-0.5 top-0.5 flex h-4 min-w-4 items-center justify-center rounded-full bg-rose-600 px-1 text-[10px] font-bold text-white shadow-xs ring-2 ring-white"
            aria-hidden="true"
          >
            {badgeText}
          </span>
        )}
      </button>

      {/* Screen reader live notification announcement */}
      <span className="sr-only" role="status" aria-live="polite">
        {unreadCount > 0 ? `Bạn có ${unreadCount} thông báo chưa đọc.` : ''}
      </span>

      {isDropdownOpen && (
        <NotificationDropdown
          request={request}
          triggerRef={buttonRef}
          onClose={closeDropdown}
          onSelectNotification={handleSelectNotification}
        />
      )}
    </div>
  );
}
