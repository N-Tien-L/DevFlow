export interface NotificationResponse {
  id: string;
  eventId: string;
  workspaceId: string;
  type: string;
  title: string;
  content: string;
  referenceId: string | null;
  isRead: boolean;
  createdAt: string;
  readAt: string | null;
}

export interface NotificationPage {
  content: NotificationResponse[];
  page: number;
  size: number;
  hasNext: boolean;
}

export interface UnreadCountResponse {
  count: number;
}

export interface MarkAllReadResponse {
  updated: number;
}
