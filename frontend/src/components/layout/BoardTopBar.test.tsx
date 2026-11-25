// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as authModule from '../../auth/AuthProvider';
import { useNotificationStore } from '../../stores/notificationStore';
import { BoardTopBar } from './BoardTopBar';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

describe('BoardTopBar', () => {
  let root: Root | null = null;
  let container: HTMLDivElement | null = null;

  beforeEach(() => {
    useNotificationStore.getState().reset();
    container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);

    vi.spyOn(authModule, 'useAuth').mockReturnValue({
      user: { id: 'u-1', email: 'xuan@devflow.vn', fullName: 'Lê Viết Xuân', avatarUrl: null },
      ready: true,
      sessionGeneration: 1,
      sessionEnd: null,
      signIn: vi.fn(),
      signOut: vi.fn(),
      getValidAccessToken: vi.fn(),
      request: vi.fn() as unknown as ReturnType<typeof authModule.useAuth>['request'],
    });
  });

  afterEach(() => {
    if (root) {
      act(() => root?.unmount());
      root = null;
    }
    container?.remove();
    container = null;
    vi.restoreAllMocks();
  });

  it('shows active workspace role, archived indicator, reconnect trigger, and restores focus when account menu closes on Escape', async () => {
    const onRetryConnection = vi.fn();
    const onSignOut = vi.fn();

    await act(async () => {
      root?.render(
        <MemoryRouter>
          <BoardTopBar
            user={{ id: 'u-1', email: 'xuan@devflow.vn', fullName: 'Lê Viết Xuân', avatarUrl: null }}
            workspaces={[{ id: 'ws-1', name: 'DevFlow Core', slug: 'devflow-core', role: 'OWNER', createdAt: '2026-10-01T00:00:00Z' }]}
            workspaceId="ws-1"
            boards={[{ id: 'b-1', workspaceId: 'ws-1', name: 'Sprint Archive', description: null, archived: true, createdAt: '2026-10-01T00:00:00Z', updatedAt: '2026-10-01T00:00:00Z' }]}
            boardId="b-1"
            currentBoardArchived
            connectionStatus="error"
            syncBlocked
            onWorkspaceChange={vi.fn()}
            onBoardChange={vi.fn()}
            onSelectTask={vi.fn()}
            onRetrySync={vi.fn()}
            onRetryConnection={onRetryConnection}
            onSignOut={onSignOut}
          />
        </MemoryRouter>,
      );
    });

    expect(container?.textContent).toContain('Chủ sở hữu');
    expect(container?.textContent).toContain('Lưu trữ');

    const reconnectButton = container?.querySelector('button[aria-label*="Không kết nối được đồng bộ realtime"]') as HTMLButtonElement;
    expect(reconnectButton).not.toBeNull();
    await act(async () => {
      reconnectButton.click();
    });
    expect(onRetryConnection).toHaveBeenCalledTimes(1);

    const accountTrigger = container?.querySelector('button[aria-haspopup="menu"]') as HTMLButtonElement;
    expect(accountTrigger).not.toBeNull();

    await act(async () => {
      accountTrigger.click();
    });

    const menu = container?.querySelector('[role="menu"]');
    expect(menu).not.toBeNull();
    expect(menu?.textContent).toContain('Tất cả workspace');
    expect(menu?.textContent).toContain('Tạo workspace mới');

    await act(async () => {
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    });

    expect(container?.querySelector('[role="menu"]')).toBeNull();
    expect(document.activeElement).toBe(accountTrigger);
  });
});
