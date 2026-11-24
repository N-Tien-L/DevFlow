import type { ApiRequest } from './boardApi';
import type {
  MarkAllReadResponse,
  NotificationPage,
  NotificationResponse,
  UnreadCountResponse,
} from '../types/notification';

export function listNotifications(
  request: ApiRequest,
  page = 0,
  size = 20,
  signal?: AbortSignal
): Promise<NotificationPage> {
  const params = new URLSearchParams({
    page: String(Math.max(0, page)),
    size: String(Math.max(1, Math.min(100, size))),
  });
  return request<NotificationPage>(`/api/v1/notifications?${params.toString()}`, { signal });
}

export function getUnreadCount(
  request: ApiRequest,
  signal?: AbortSignal
): Promise<UnreadCountResponse> {
  return request<UnreadCountResponse>('/api/v1/notifications/unread-count', { signal });
}

export function markAsRead(
  request: ApiRequest,
  id: string,
  signal?: AbortSignal
): Promise<NotificationResponse> {
  return request<NotificationResponse>(`/api/v1/notifications/${encodeURIComponent(id)}/read`, {
    method: 'PATCH',
    signal,
  });
}

export function markAllAsRead(
  request: ApiRequest,
  signal?: AbortSignal
): Promise<MarkAllReadResponse> {
  return request<MarkAllReadResponse>('/api/v1/notifications/read-all', {
    method: 'POST',
    signal,
  });
}
