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

  it('fetches notifications only once when API returns empty list', async () => {
    mockRequest.mockResolvedValue({
      content: [],
      page: 0,
      size: 20,
      hasNext: false,
    });

    useNotificationStore.setState({ isDropdownOpen: true });

    await act(async () => {
      root?.render(<NotificationBell />);
    });

    // Wait a microtask flush to ensure no re-fetch loop occurs
    await act(async () => {
      await Promise.resolve();
    });

    expect(mockRequest).toHaveBeenCalledTimes(1);
    expect(container?.textContent).toContain('Bạn chưa có thông báo nào');
  });

  it('does not loop fetches when API returns an error', async () => {
    mockRequest.mockRejectedValue(new Error('Network down'));

    useNotificationStore.setState({ isDropdownOpen: true });

    await act(async () => {
      root?.render(<NotificationBell />);
    });

    await act(async () => {
      await Promise.resolve();
    });

    expect(mockRequest).toHaveBeenCalledTimes(1);
    expect(container?.textContent).toContain('Network down');
  });

  it('closes dropdown cleanly when bell button is clicked while open (no double-toggle)', async () => {
    useNotificationStore.setState({
      notifications: [sampleNotification],
      hasLoaded: true,
      unreadCount: 1,
      isDropdownOpen: true,
    });

    await act(async () => {
      root?.render(<NotificationBell />);
    });

    const button = container?.querySelector('#notification-bell-button') as HTMLButtonElement;
    expect(container?.querySelector('[role="region"]')).not.toBeNull();

    // Simulate real browser sequence: mousedown on button, then click on button
    await act(async () => {
      button.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
      button.click();
    });

    expect(useNotificationStore.getState().isDropdownOpen).toBe(false);
    expect(container?.querySelector('[role="region"]')).toBeNull();
  });

  it('invokes onSelectTask only for valid UUID referenceIds', async () => {
    const onSelectTask = vi.fn();
    const taskUuid = '11111111-2222-3333-4444-555555555555';
    const linkedNotif: NotificationResponse = {
      ...sampleNotification,
      id: 'notif-uuid',
      referenceId: taskUuid,
      isRead: true,
    };
    const nonUuidNotif: NotificationResponse = {
      ...sampleNotification,
      id: 'notif-pipe',
      title: 'Pipeline alert',
      referenceId: 'pipe-999',
      isRead: true,
    };

    useNotificationStore.setState({
      notifications: [nonUuidNotif, linkedNotif],
      hasLoaded: true,
      unreadCount: 0,
      isDropdownOpen: true,
    });

    await act(async () => {
      root?.render(<NotificationBell onSelectTask={onSelectTask} />);
    });

    const items = container?.querySelectorAll('[role="button"]') ?? [];
    await act(async () => {
      (items[0] as HTMLElement).click();
    });
    expect(onSelectTask).not.toHaveBeenCalled();

    await act(async () => {
      (items[1] as HTMLElement).click();
    });
    expect(onSelectTask).toHaveBeenCalledWith(taskUuid);
  });
});
