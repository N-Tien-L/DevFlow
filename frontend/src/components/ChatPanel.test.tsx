// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { useNotificationStore } from '../stores/notificationStore';
import ChatPanel from './ChatPanel';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

describe('ChatPanel', () => {
  let root: Root | null = null;
  let container: HTMLDivElement | null = null;

  beforeEach(() => {
    useNotificationStore.getState().reset();
    container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
  });

  afterEach(() => {
    if (root) {
      act(() => root?.unmount());
      root = null;
    }
    container?.remove();
    container = null;
  });

  it('opens with disconnected AI status, available actions, and restores focus on Escape', async () => {
    await act(async () => {
      root?.render(
        <MemoryRouter initialEntries={['/boards/board-1']}>
          <ChatPanel />
        </MemoryRouter>,
      );
    });

    const launcher = container?.querySelector('button[aria-controls="devflow-ai-chat-panel"]') as HTMLButtonElement;
    expect(launcher).not.toBeNull();
    expect(container?.querySelector('#devflow-ai-chat-panel')).toBeNull();

    await act(async () => {
      launcher.click();
    });

    const panel = container?.querySelector('#devflow-ai-chat-panel');
    expect(panel).not.toBeNull();
    expect(panel?.textContent).toContain('Chưa kết nối dịch vụ AI');
    expect(panel?.textContent).toContain('Thao tác khả dụng ngay');
    expect(panel?.querySelector<HTMLInputElement>('#devflow-chat-input')?.disabled).toBe(true);

    await act(async () => {
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    });

    expect(container?.querySelector('#devflow-ai-chat-panel')).toBeNull();
    expect(document.activeElement).toBe(launcher);
  });
});
