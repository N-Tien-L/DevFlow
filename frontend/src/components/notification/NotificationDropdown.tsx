import { useEffect, useRef, type RefObject } from 'react';
import type { ApiRequest } from '../../api/boardApi';
import { useNotificationStore } from '../../stores/notificationStore';
import type { NotificationResponse } from '../../types/notification';
import { NotificationItem } from './NotificationItem';

interface NotificationDropdownProps {
  request: ApiRequest;
  triggerRef?: RefObject<HTMLElement | null>;
  onClose: () => void;
  onSelectNotification?: (notification: NotificationResponse) => void;
}

export function NotificationDropdown({
  request,
  triggerRef,
  onClose,
  onSelectNotification,
}: NotificationDropdownProps) {
  const {
    notifications,
    unreadCount,
    hasLoaded,
    isLoading,
    isLoadingMore,
    hasMore,
    error,
    fetchNotifications,
    loadMore,
    markAsRead,
    markAllAsRead,
  } = useNotificationStore();

  const dropdownRef = useRef<HTMLDivElement>(null);
  const didAttemptFetchRef = useRef(false);

  // Lazy load notifications once on open when not yet loaded (guards against infinite loop on empty/error)
  useEffect(() => {
    if (!didAttemptFetchRef.current && !hasLoaded && !isLoading && !error) {
      didAttemptFetchRef.current = true;
      void fetchNotifications(request, 0);
    }
  }, [error, fetchNotifications, hasLoaded, isLoading, request]);

  // Click outside and Escape key listeners
  useEffect(() => {
    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        onClose();
      }
    }

    function handlePointerDown(event: MouseEvent | TouchEvent) {
      const target = event.target as Node;
      if (triggerRef?.current && triggerRef.current.contains(target)) {
        return;
      }
      if (dropdownRef.current && !dropdownRef.current.contains(target)) {
        onClose();
      }
    }

    document.addEventListener('keydown', handleKeyDown);
    document.addEventListener('mousedown', handlePointerDown);
    document.addEventListener('touchstart', handlePointerDown);

    return () => {
      document.removeEventListener('keydown', handleKeyDown);
      document.removeEventListener('mousedown', handlePointerDown);
      document.removeEventListener('touchstart', handlePointerDown);
    };
  }, [onClose, triggerRef]);

  const handleMarkAll = () => {
    void markAllAsRead(request).catch(() => {});
  };

  const handleMarkItemRead = (id: string) => {
    void markAsRead(request, id).catch(() => {});
  };

  return (
    <div
      ref={dropdownRef}
      className="absolute right-0 top-full mt-2 w-80 sm:w-96 rounded-2xl border border-slate-200/90 bg-white shadow-xl shadow-slate-900/10 z-50 overflow-hidden flex flex-col"
      role="region"
      aria-label="Danh sách thông báo"
    >
      {/* Header */}
      <div className="flex items-center justify-between border-b border-slate-100 bg-slate-50/70 px-4 py-3">
        <div className="flex items-center gap-2">
          <h2 className="text-sm font-semibold text-slate-900">Thông báo</h2>
          {unreadCount > 0 && (
            <span className="rounded-full bg-rose-100 px-2 py-0.5 text-[11px] font-bold text-rose-700">
              {unreadCount > 99 ? '99+' : unreadCount} mới
            </span>
          )}
        </div>
        <button
          type="button"
          disabled={unreadCount === 0 || isLoading}
          onClick={handleMarkAll}
          className="text-xs font-medium text-indigo-600 hover:text-indigo-800 disabled:opacity-40 disabled:hover:text-indigo-600 transition focus:outline-none focus:underline"
        >
          Đánh dấu tất cả đã đọc
        </button>
      </div>

      {/* Content Area */}
      <div className="max-h-96 overflow-y-auto divide-y divide-slate-100 min-h-[140px] flex flex-col">
        {error && (
          <div className="p-4 text-center text-xs text-rose-600 bg-rose-50/50">
            <p>{error}</p>
            <button
              type="button"
              onClick={() => void fetchNotifications(request, 0)}
              className="mt-2 font-semibold underline hover:text-rose-800"
            >
              Thử tải lại
            </button>
          </div>
        )}

        {isLoading && notifications.length === 0 && (
          <div className="grid flex-1 place-items-center py-10 text-xs text-slate-400">
            <div className="flex items-center gap-2">
              <svg className="size-4 animate-spin text-indigo-600" fill="none" viewBox="0 0 24 24">
                <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" />
              </svg>
              <span>Đang tải thông báo…</span>
            </div>
          </div>
        )}

        {!isLoading && !error && notifications.length === 0 && (
          <div className="grid flex-1 place-items-center py-12 text-center">
            <div className="grid size-10 place-items-center rounded-full bg-slate-100 text-slate-400 mb-2">
              <svg className="size-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
                <path strokeLinecap="round" strokeLinejoin="round" d="M15 17h5l-1.405-1.405A2.032 2.032 0 0118 14.158V11a6.002 6.002 0 00-4-5.659V5a2 2 0 10-4 0v.341C7.67 6.165 6 8.388 6 11v3.159c0 .538-.214 1.055-.595 1.436L4 17h5m6 0v1a3 3 0 11-6 0v-1m6 0H9" />
              </svg>
            </div>
            <p className="text-xs font-medium text-slate-600">Bạn chưa có thông báo nào</p>
            <p className="text-[11px] text-slate-400 mt-0.5">Thông báo sẽ xuất hiện khi có thay đổi công việc hoặc CI</p>
          </div>
        )}

        {notifications.map((item) => (
          <NotificationItem
            key={item.id}
            notification={item}
            onMarkRead={handleMarkItemRead}
            onSelect={onSelectNotification}
          />
        ))}

        {hasMore && (
          <div className="p-2 text-center bg-slate-50/50">
            <button
              type="button"
              disabled={isLoadingMore}
              onClick={() => void loadMore(request)}
              className="w-full py-1.5 text-xs font-semibold text-slate-600 hover:text-indigo-600 hover:bg-slate-100 rounded-lg transition disabled:opacity-50"
            >
              {isLoadingMore ? 'Đang tải thêm…' : 'Xem thêm thông báo'}
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
