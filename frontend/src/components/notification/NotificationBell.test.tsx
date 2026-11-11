// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as authModule from '../../auth/AuthProvider';
import { useNotificationStore } from '../../stores/notificationStore';
import type { NotificationResponse } from '../../types/notification';
import { NotificationBell } from './NotificationBell';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const sampleNotification: NotificationResponse = {
  id: 'notif-1',
  eventId: 'evt-1',
  workspaceId: 'ws-1',
  type: 'ci.failure_summarized',
  title: 'CI pipeline failed on main',
  content: 'Build 42 failed in test step',
  referenceId: 'task-99',
  isRead: false,
  createdAt: new Date().toISOString(),
  readAt: null,
};

describe('NotificationBell Component', () => {
  let root: Root | null = null;
  let container: HTMLDivElement | null = null;
  const mockRequest = vi.fn().mockResolvedValue({
    content: [sampleNotification],
    page: 0,
    size: 20,
    hasNext: false,
  });

  beforeEach(() => {
    vi.clearAllMocks();
    useNotificationStore.getState().reset();
    container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);

    vi.spyOn(authModule, 'useAuth').mockReturnValue({
      user: { id: 'u-1', email: 'user@devflow.dev', fullName: 'Test User', avatarUrl: null },
      ready: true,
      sessionGeneration: 1,
      sessionEnd: null,
      signIn: vi.fn(),
      signOut: vi.fn(),
      getValidAccessToken: vi.fn(),
      request: mockRequest as unknown as ReturnType<typeof authModule.useAuth>['request'],
    });
  });

  afterEach(() => {
    if (root) {
      act(() => root?.unmount());
      root = null;
    }
    if (container && container.parentNode) {
      container.parentNode.removeChild(container);
      container = null;
    }
    vi.restoreAllMocks();
  });

  it('renders bell button without unread badge when unreadCount is 0', async () => {
    useNotificationStore.setState({ unreadCount: 0 });

    await act(async () => {
      root?.render(<NotificationBell />);
    });

    const button = container?.querySelector('#notification-bell-button');
    expect(button).not.toBeNull();
    expect(button?.getAttribute('aria-label')).toBe('Thông báo');

    // Badge should not be rendered
    const badge = button?.querySelector('span[aria-hidden="true"]');
    expect(badge).toBeNull();
  });

  it('renders badge with count when unreadCount > 0', async () => {
    useNotificationStore.setState({ unreadCount: 4 });

    await act(async () => {
      root?.render(<NotificationBell />);
    });

    const button = container?.querySelector('#notification-bell-button');
    expect(button?.getAttribute('aria-label')).toBe('Thông báo (4 chưa đọc)');

    const badge = button?.querySelector('span[aria-hidden="true"]');
    expect(badge?.textContent).toBe('4');
  });

  it('renders 99+ when unreadCount exceeds 99', async () => {
    useNotificationStore.setState({ unreadCount: 150 });

    await act(async () => {
      root?.render(<NotificationBell />);
    });

    const button = container?.querySelector('#notification-bell-button');
    const badge = button?.querySelector('span[aria-hidden="true"]');
    expect(badge?.textContent).toBe('99+');
  });

  it('toggles dropdown when clicked', async () => {
    useNotificationStore.setState({
      notifications: [sampleNotification],
      unreadCount: 1,
      isDropdownOpen: false,
    });

    await act(async () => {
      root?.render(<NotificationBell />);
    });

    const button = container?.querySelector('#notification-bell-button') as HTMLButtonElement;
    expect(container?.querySelector('[role="region"]')).toBeNull();

    await act(async () => {
      button.click();
    });

    const dropdown = container?.querySelector('[role="region"]');
    expect(dropdown).not.toBeNull();
    expect(dropdown?.textContent).toContain('Thông báo');
    expect(dropdown?.textContent).toContain('CI pipeline failed on main');
  });

  it('closes dropdown on Escape key', async () => {
    useNotificationStore.setState({
      notifications: [sampleNotification],
      unreadCount: 1,
      isDropdownOpen: true,
    });

    await act(async () => {
      root?.render(<NotificationBell />);
    });

    expect(container?.querySelector('[role="region"]')).not.toBeNull();

    await act(async () => {
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    });

    expect(useNotificationStore.getState().isDropdownOpen).toBe(false);
  });
});
