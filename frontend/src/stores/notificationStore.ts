import { create } from 'zustand';
import type { ApiRequest } from '../api/boardApi';
import {
  getUnreadCount,
  listNotifications,
  markAllAsRead as markAllAsReadApi,
  markAsRead as markAsReadApi,
} from '../api/notificationApi';
import type { NotificationResponse } from '../types/notification';

export interface NotificationState {
  notifications: NotificationResponse[];
  unreadCount: number;
  isLoading: boolean;
  isLoadingMore: boolean;
  hasMore: boolean;
  currentPage: number;
  isDropdownOpen: boolean;
  error: string | null;

  // Actions
  fetchNotifications: (request: ApiRequest, page?: number) => Promise<void>;
  fetchUnreadCount: (request: ApiRequest) => Promise<void>;
  loadMore: (request: ApiRequest) => Promise<void>;
  markAsRead: (request: ApiRequest, id: string) => Promise<void>;
  markAllAsRead: (request: ApiRequest) => Promise<void>;
  addRealtimeNotification: (notification: NotificationResponse) => void;
  toggleDropdown: () => void;
  closeDropdown: () => void;
  setDropdownOpen: (open: boolean) => void;
  reset: () => void;
}

const initialState = {
  notifications: [] as NotificationResponse[],
  unreadCount: 0,
  isLoading: false,
  isLoadingMore: false,
  hasMore: false,
  currentPage: 0,
  isDropdownOpen: false,
  error: null as string | null,
};

export const useNotificationStore = create<NotificationState>((set, get) => ({
  ...initialState,

  fetchUnreadCount: async (request: ApiRequest) => {
    try {
      const res = await getUnreadCount(request);
      set({ unreadCount: Math.max(0, res.count) });
    } catch {
      // Retain current count on transient network error
    }
  },

  fetchNotifications: async (request: ApiRequest, page = 0) => {
    const isFirstPage = page === 0;
    if (isFirstPage) {
      set({ isLoading: true, error: null });
    } else {
      set({ isLoadingMore: true, error: null });
    }

    try {
      const res = await listNotifications(request, page, 20);
      if (isFirstPage) {
        set({
          notifications: res.content,
          currentPage: res.page,
          hasMore: res.hasNext,
          isLoading: false,
        });
      } else {
        const existingIds = new Set(get().notifications.map((n) => n.id));
        const newItems = res.content.filter((n) => !existingIds.has(n.id));
        set({
          notifications: [...get().notifications, ...newItems],
          currentPage: res.page,
          hasMore: res.hasNext,
          isLoadingMore: false,
        });
      }
    } catch (err) {
      set({
        error: err instanceof Error ? err.message : 'Không thể tải thông báo.',
        isLoading: false,
        isLoadingMore: false,
      });
    }
  },

  loadMore: async (request: ApiRequest) => {
    const { isLoading, isLoadingMore, hasMore, currentPage, fetchNotifications } = get();
    if (isLoading || isLoadingMore || !hasMore) return;
    await fetchNotifications(request, currentPage + 1);
  },

  markAsRead: async (request: ApiRequest, id: string) => {
    const prevNotifications = get().notifications;
    const prevCount = get().unreadCount;
    const target = prevNotifications.find((n) => n.id === id);

    // If item not found or already read, no-op
    if (!target || target.isRead) return;

    // Optimistic update
    set({
      notifications: prevNotifications.map((n) =>
        n.id === id ? { ...n, isRead: true, readAt: new Date().toISOString() } : n
      ),
      unreadCount: Math.max(0, prevCount - 1),
    });

    try {
      await markAsReadApi(request, id);
    } catch (err) {
      // Rollback on failure
      set({
        notifications: prevNotifications,
        unreadCount: prevCount,
      });
      throw err;
    }
  },

  markAllAsRead: async (request: ApiRequest) => {
    const prevNotifications = get().notifications;
    const prevCount = get().unreadCount;
    const hasUnread = prevCount > 0 || prevNotifications.some((n) => !n.isRead);

    if (!hasUnread) return;

    // Optimistic update
    const nowIso = new Date().toISOString();
    set({
      notifications: prevNotifications.map((n) =>
        n.isRead ? n : { ...n, isRead: true, readAt: nowIso }
      ),
      unreadCount: 0,
    });

    try {
      await markAllAsReadApi(request);
    } catch (err) {
      // Rollback on failure
      set({
        notifications: prevNotifications,
        unreadCount: prevCount,
      });
      throw err;
    }
  },

  addRealtimeNotification: (notification: NotificationResponse) => {
    const { notifications, unreadCount } = get();
    const existingIndex = notifications.findIndex((n) => n.id === notification.id);

    if (existingIndex !== -1) {
      const oldItem = notifications[existingIndex];
      const updated = [...notifications];
      updated[existingIndex] = notification;

      let nextCount = unreadCount;
      if (!notification.isRead && oldItem.isRead) nextCount += 1;
      else if (notification.isRead && !oldItem.isRead) nextCount = Math.max(0, nextCount - 1);

      set({ notifications: updated, unreadCount: nextCount });
      return;
    }

    set({
      notifications: [notification, ...notifications],
      unreadCount: notification.isRead ? unreadCount : unreadCount + 1,
    });
  },

  toggleDropdown: () => {
    set((state) => ({ isDropdownOpen: !state.isDropdownOpen }));
  },

  closeDropdown: () => {
    set({ isDropdownOpen: false });
  },

  setDropdownOpen: (open: boolean) => {
    set({ isDropdownOpen: open });
  },

  reset: () => {
    set(initialState);
  },
}));
