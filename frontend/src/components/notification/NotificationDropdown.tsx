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

  // Click outside and Escape key listeners (with focus restoration on Escape)
  useEffect(() => {
    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        onClose();
        triggerRef?.current?.focus();
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
      className="fixed inset-x-3 top-16 z-50 flex flex-col overflow-hidden rounded-2xl border border-slate-200/90 bg-white shadow-xl shadow-slate-900/12 sm:absolute sm:inset-x-auto sm:right-0 sm:top-full sm:mt-2 sm:w-96"
      role="region"
      aria-label="Danh sách thông báo"
    >
      {/* Header */}
      <div className="flex items-center justify-between gap-2 border-b border-slate-200/80 bg-slate-50/80 px-4 py-3">
        <div className="flex items-center gap-2">
          <h2 className="text-sm font-bold text-slate-950">Thông báo</h2>
          {unreadCount > 0 && (
            <span className="tabular-nums rounded-full bg-rose-100 px-2 py-0.5 text-[11px] font-bold text-rose-700">
              {unreadCount > 99 ? '99+' : unreadCount} mới
            </span>
          )}
        </div>
        <button
          type="button"
          disabled={unreadCount === 0 || isLoading}
          onClick={handleMarkAll}
          className="min-h-8 rounded-lg px-2 py-1 text-xs font-semibold text-indigo-600 transition hover:bg-indigo-50 hover:text-indigo-800 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 disabled:opacity-40 disabled:hover:bg-transparent disabled:hover:text-indigo-600"
        >
          Đánh dấu tất cả đã đọc
        </button>
      </div>

      {/* Content Area */}
      <div className="devflow-scroll flex max-h-[min(65dvh,400px)] min-h-[150px] flex-col divide-y divide-slate-100 overflow-y-auto">
        {error && (
          <div className="bg-rose-50/75 p-4 text-center text-xs text-rose-700" role="alert">
            <p className="font-medium">{error}</p>
            <button
              type="button"
              onClick={() => void fetchNotifications(request, 0)}
              className="mt-2 inline-flex min-h-8 items-center rounded-lg bg-white px-3 py-1 font-semibold text-rose-700 ring-1 ring-inset ring-rose-200 hover:bg-rose-100/60"
            >
              Thử tải lại
            </button>
          </div>
        )}

        {isLoading && notifications.length === 0 && (
          <div className="grid flex-1 place-items-center py-10 text-xs text-slate-500" role="status">
            <div className="flex items-center gap-2">
              <svg className="size-4 animate-spin text-indigo-600 motion-reduce:animate-none" fill="none" viewBox="0 0 24 24" aria-hidden="true">
                <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" />
              </svg>
              <span>Đang tải thông báo…</span>
            </div>
          </div>
        )}

        {!isLoading && !error && notifications.length === 0 && (
          <div className="grid flex-1 place-items-center px-4 py-10 text-center">
            <div className="mb-2.5 grid size-10 place-items-center rounded-xl bg-slate-100 text-slate-500">
              <svg className="size-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.6} aria-hidden="true">
                <path strokeLinecap="round" strokeLinejoin="round" d="M15 17h5l-1.405-1.405A2.032 2.032 0 0118 14.158V11a6.002 6.002 0 00-4-5.659V5a2 2 0 10-4 0v.341C7.67 6.165 6 8.388 6 11v3.159c0 .538-.214 1.055-.595 1.436L4 17h5m6 0v1a3 3 0 11-6 0v-1m6 0H9" />
              </svg>
            </div>
            <p className="text-xs font-semibold text-slate-700">Bạn chưa có thông báo nào</p>
            <p className="mt-1 text-[11px] leading-4 text-slate-500">Thông báo sẽ xuất hiện khi có cập nhật công việc, PR hoặc cảnh báo CI.</p>
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
          <div className="bg-slate-50/70 p-2 text-center">
            <button
              type="button"
              disabled={isLoadingMore}
              onClick={() => void loadMore(request)}
              className="min-h-9 w-full rounded-xl py-1.5 text-xs font-semibold text-slate-700 transition hover:bg-slate-100 hover:text-indigo-700 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 disabled:opacity-50"
            >
              {isLoadingMore ? 'Đang tải thêm…' : 'Xem thêm thông báo'}
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
