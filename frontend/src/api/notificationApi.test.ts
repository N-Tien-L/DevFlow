import { describe, expect, it, vi } from 'vitest';
import {
  getUnreadCount,
  listNotifications,
  markAllAsRead,
  markAsRead,
} from './notificationApi';
import type { ApiRequest } from './boardApi';

describe('notificationApi', () => {
  it('calls listNotifications with default page and size params', async () => {
    const mockRequest = vi.fn().mockResolvedValue({
      content: [],
      page: 0,
      size: 20,
      hasNext: false,
    }) as unknown as ApiRequest;

    const result = await listNotifications(mockRequest);
    expect(mockRequest).toHaveBeenCalledWith('/api/v1/notifications?page=0&size=20', {
      signal: undefined,
    });
    expect(result.content).toEqual([]);
  });

  it('calls listNotifications with custom page and size bounds', async () => {
    const mockRequest = vi.fn().mockResolvedValue({
      content: [],
      page: 2,
      size: 50,
      hasNext: true,
    }) as unknown as ApiRequest;

    await listNotifications(mockRequest, 2, 50);
    expect(mockRequest).toHaveBeenCalledWith('/api/v1/notifications?page=2&size=50', {
      signal: undefined,
    });
  });

  it('calls getUnreadCount', async () => {
    const mockRequest = vi.fn().mockResolvedValue({ count: 5 }) as unknown as ApiRequest;

    const result = await getUnreadCount(mockRequest);
    expect(mockRequest).toHaveBeenCalledWith('/api/v1/notifications/unread-count', {
      signal: undefined,
    });
    expect(result.count).toBe(5);
  });

  it('calls markAsRead with PATCH method', async () => {
    const mockResponse = {
      id: 'notif-123',
      eventId: 'evt-1',
      workspaceId: 'ws-1',
      type: 'task.created',
      title: 'New Task',
      content: 'Task content',
      referenceId: 'task-1',
      isRead: true,
      createdAt: '2026-11-11T00:00:00Z',
      readAt: '2026-11-11T00:01:00Z',
    };
    const mockRequest = vi.fn().mockResolvedValue(mockResponse) as unknown as ApiRequest;

    const result = await markAsRead(mockRequest, 'notif-123');
    expect(mockRequest).toHaveBeenCalledWith('/api/v1/notifications/notif-123/read', {
      method: 'PATCH',
      signal: undefined,
    });
    expect(result.isRead).toBe(true);
  });

  it('calls markAllAsRead with POST method', async () => {
    const mockRequest = vi.fn().mockResolvedValue({ updated: 3 }) as unknown as ApiRequest;

    const result = await markAllAsRead(mockRequest);
    expect(mockRequest).toHaveBeenCalledWith('/api/v1/notifications/read-all', {
      method: 'POST',
      signal: undefined,
    });
    expect(result.updated).toBe(3);
  });
});
