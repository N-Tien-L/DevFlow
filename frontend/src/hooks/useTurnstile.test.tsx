// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useTurnstile } from './useTurnstile';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

type Turnstile = ReturnType<typeof useTurnstile>;

let root: Root | null = null;
let turnstile: Turnstile;

function Probe() {
  turnstile = useTurnstile('register');
  return <div ref={turnstile.hostRef} />;
}

const settle = () => act(() => new Promise<void>((resolve) => setTimeout(resolve, 0)));
const scripts = () => document.querySelectorAll<HTMLScriptElement>('#cloudflare-turnstile-script');

beforeEach(() => {
  vi.stubEnv('VITE_TURNSTILE_SITE_KEY', 'site-key');
});

afterEach(async () => {
  await act(async () => root?.unmount());
  root = null;
  delete window.turnstile;
  document.head.innerHTML = '';
  vi.unstubAllEnvs();
});

describe('useTurnstile', () => {
  it('replaces a script that failed to load so a retry can succeed', async () => {
    root = createRoot(document.createElement('div'));
    await act(async () => root?.render(<Probe />));
    expect(scripts()).toHaveLength(1);
    const failed = scripts()[0];

    await act(async () => { failed.dispatchEvent(new Event('error')); });
    await settle();
    expect(turnstile.status).toBe('error');
    expect(scripts()).toHaveLength(0);

    await act(async () => turnstile.reset());
    await settle();
    expect(scripts()).toHaveLength(1);
    expect(scripts()[0]).not.toBe(failed);

    let options: Parameters<NonNullable<typeof window.turnstile>['render']>[1] | undefined;
    window.turnstile = {
      render: vi.fn((_element, renderOptions) => { options = renderOptions; return 'widget-1'; }),
      reset: vi.fn(),
      remove: vi.fn(),
    };
    await act(async () => { scripts()[0].dispatchEvent(new Event('load')); });
    await settle();
    expect(window.turnstile.render).toHaveBeenCalledOnce();
    expect(turnstile.status).toBe('pending');

    await act(async () => options?.callback('token-1'));
    expect(turnstile).toMatchObject({ status: 'verified', token: 'token-1', satisfied: true });
  });
});
