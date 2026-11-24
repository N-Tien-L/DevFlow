import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ApiRequest } from '../api/boardApi';
import * as api from '../api/notificationApi';
import type { NotificationResponse } from '../types/notification';
import { useNotificationStore } from './notificationStore';

vi.mock('../api/notificationApi', () => ({
  getUnreadCount: vi.fn(),
  listNotifications: vi.fn(),
  markAsRead: vi.fn(),
  markAllAsRead: vi.fn(),
}));

const dummyRequest = vi.fn() as unknown as ApiRequest;

const sampleItem1: NotificationResponse = {
  id: 'n-1',
  eventId: 'e-1',
  workspaceId: 'w-1',
  type: 'task.created',
  title: 'Task Created',
  content: 'Content 1',
  referenceId: 'ref-1',
  isRead: false,
  createdAt: '2026-11-11T00:00:00Z',
  readAt: null,
};

const sampleItem2: NotificationResponse = {
  id: 'n-2',
  eventId: 'e-2',
  workspaceId: 'w-1',
  type: 'ci.failure_summarized',
  title: 'CI Failure',
  content: 'Content 2',
  referenceId: 'ref-2',
  isRead: false,
  createdAt: '2026-11-11T00:01:00Z',
  readAt: null,
};

describe('notificationStore', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useNotificationStore.getState().reset();
  });

  it('initializes with default state', () => {
    const state = useNotificationStore.getState();
    expect(state.notifications).toEqual([]);
    expect(state.unreadCount).toBe(0);
    expect(state.isLoading).toBe(false);
    expect(state.isDropdownOpen).toBe(false);
    expect(state.hasMore).toBe(false);
    expect(state.currentPage).toBe(0);
  });

  it('fetches unread count and updates store', async () => {
    vi.mocked(api.getUnreadCount).mockResolvedValueOnce({ count: 7 });

    await useNotificationStore.getState().fetchUnreadCount(dummyRequest);
    expect(useNotificationStore.getState().unreadCount).toBe(7);
    expect(api.getUnreadCount).toHaveBeenCalledWith(dummyRequest);
  });

  it('fetches first page of notifications and updates list', async () => {
    vi.mocked(api.listNotifications).mockResolvedValueOnce({
      content: [sampleItem1],
      page: 0,
      size: 20,
      hasNext: true,
    });

    await useNotificationStore.getState().fetchNotifications(dummyRequest, 0);

    const state = useNotificationStore.getState();
    expect(state.notifications).toEqual([sampleItem1]);
    expect(state.currentPage).toBe(0);
    expect(state.hasMore).toBe(true);
    expect(state.isLoading).toBe(false);
  });

  it('appends notifications on subsequent pages and deduplicates', async () => {
    useNotificationStore.setState({
      notifications: [sampleItem1],
      currentPage: 0,
      hasMore: true,
    });

    vi.mocked(api.listNotifications).mockResolvedValueOnce({
      content: [sampleItem1, sampleItem2], // sampleItem1 is duplicate
      page: 1,
      size: 20,
      hasNext: false,
    });

    await useNotificationStore.getState().fetchNotifications(dummyRequest, 1);

    const state = useNotificationStore.getState();
    expect(state.notifications).toHaveLength(2);
    expect(state.notifications[0].id).toBe('n-1');
    expect(state.notifications[1].id).toBe('n-2');
    expect(state.currentPage).toBe(1);
    expect(state.hasMore).toBe(false);
  });

  it('optimistically marks a notification as read and rolls back on error', async () => {
    useNotificationStore.setState({
      notifications: [sampleItem1, sampleItem2],
      unreadCount: 2,
    });

    vi.mocked(api.markAsRead).mockRejectedValueOnce(new Error('Network error'));

    await expect(
      useNotificationStore.getState().markAsRead(dummyRequest, 'n-1')
    ).rejects.toThrow('Network error');

    // Rolled back
    const state = useNotificationStore.getState();
    expect(state.unreadCount).toBe(2);
    expect(state.notifications[0].isRead).toBe(false);
  });

  it('optimistically marks a notification as read on success', async () => {
    useNotificationStore.setState({
      notifications: [sampleItem1, sampleItem2],
      unreadCount: 2,
    });

    vi.mocked(api.markAsRead).mockResolvedValueOnce({
      ...sampleItem1,
      isRead: true,
      readAt: '2026-11-11T00:05:00Z',
    });

    await useNotificationStore.getState().markAsRead(dummyRequest, 'n-1');

    const state = useNotificationStore.getState();
    expect(state.unreadCount).toBe(1);
    expect(state.notifications[0].isRead).toBe(true);
    expect(state.notifications[1].isRead).toBe(false);
  });

  it('optimistically marks all notifications as read and rolls back on error', async () => {
    useNotificationStore.setState({
      notifications: [sampleItem1, sampleItem2],
      unreadCount: 2,
    });

    vi.mocked(api.markAllAsRead).mockRejectedValueOnce(new Error('Failed to mark all'));

    await expect(
      useNotificationStore.getState().markAllAsRead(dummyRequest)
    ).rejects.toThrow('Failed to mark all');

    const state = useNotificationStore.getState();
    expect(state.unreadCount).toBe(2);
    expect(state.notifications.every((n) => !n.isRead)).toBe(true);
  });

  it('optimistically marks all notifications as read on success', async () => {
    useNotificationStore.setState({
      notifications: [sampleItem1, sampleItem2],
      unreadCount: 2,
    });

    vi.mocked(api.markAllAsRead).mockResolvedValueOnce({ updated: 2 });

    await useNotificationStore.getState().markAllAsRead(dummyRequest);

    const state = useNotificationStore.getState();
    expect(state.unreadCount).toBe(0);
    expect(state.notifications.every((n) => n.isRead)).toBe(true);
  });

  it('adds real-time notification prepending to list and incrementing unreadCount', () => {
    useNotificationStore.setState({
      notifications: [sampleItem1],
      unreadCount: 1,
    });

    useNotificationStore.getState().addRealtimeNotification(sampleItem2);

    const state = useNotificationStore.getState();
    expect(state.notifications).toHaveLength(2);
    expect(state.notifications[0].id).toBe('n-2');
    expect(state.notifications[1].id).toBe('n-1');
    expect(state.unreadCount).toBe(2);
  });

  it('toggles and closes dropdown', () => {
    const store = useNotificationStore.getState();
    expect(store.isDropdownOpen).toBe(false);

    store.toggleDropdown();
    expect(useNotificationStore.getState().isDropdownOpen).toBe(true);

    store.closeDropdown();
    expect(useNotificationStore.getState().isDropdownOpen).toBe(false);

    store.setDropdownOpen(true);
    expect(useNotificationStore.getState().isDropdownOpen).toBe(true);
  });
});
