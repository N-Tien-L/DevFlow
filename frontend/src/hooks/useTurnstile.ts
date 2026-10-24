import { useCallback, useEffect, useRef, useState } from 'react';

// Restored from the pre-#10 useTurnstileToken hook and switched to a visible managed widget for the
// public registration form. Tokens are single-use, so callers reset() after every submit.

interface TurnstileRenderOptions {
  sitekey: string;
  action?: string;
  theme?: 'auto' | 'light' | 'dark';
  language?: string;
  size?: 'normal' | 'flexible' | 'compact';
  callback(token: string): void;
  'error-callback'(): void;
  'expired-callback'(): void;
  'timeout-callback'(): void;
}

interface TurnstileApi {
  render(container: HTMLElement, options: TurnstileRenderOptions): string;
  reset(widgetId: string): void;
  remove(widgetId: string): void;
}

declare global {
  interface Window { turnstile?: TurnstileApi }
  interface ImportMetaEnv {
    readonly VITE_TURNSTILE_SITE_KEY?: string;
    readonly VITE_TURNSTILE_REQUIRED?: string;
  }
}

export type TurnstileStatus = 'disabled' | 'misconfigured' | 'loading' | 'pending' | 'verified' | 'expired' | 'error';

const SCRIPT_ID = 'cloudflare-turnstile-script';
let scriptPromise: Promise<void> | null = null;

function loadTurnstileScript() {
  if (window.turnstile) return Promise.resolve();
  if (scriptPromise) return scriptPromise;
  scriptPromise = new Promise<void>((resolve, reject) => {
    const existing = document.querySelector<HTMLScriptElement>(`#${SCRIPT_ID}`);
    const script = existing ?? document.createElement('script');
    // A failed <script> never fires again; drop it so the next attempt appends a fresh element.
    const fail = (message: string) => {
      script.remove();
      reject(new Error(message));
    };
    script.addEventListener('load', () => window.turnstile ? resolve() : fail('Turnstile unavailable'), { once: true });
    script.addEventListener('error', () => fail('Turnstile script failed to load'), { once: true });
    if (!existing) {
      script.id = SCRIPT_ID;
      script.src = 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit';
      script.async = true;
      script.defer = true;
      document.head.appendChild(script);
    }
    if (window.turnstile) resolve();
  }).catch((error: unknown) => {
    scriptPromise = null;
    throw error;
  });
  return scriptPromise;
}

function turnstileConfig() {
  const siteKey = import.meta.env.VITE_TURNSTILE_SITE_KEY?.trim() ?? '';
  // The server decides whether bot protection is enforced (TURNSTILE_ENABLED). Setting
  // VITE_TURNSTILE_REQUIRED=true only blocks the form early when a build ships without a site key.
  const required = import.meta.env.VITE_TURNSTILE_REQUIRED === 'true';
  return { siteKey, required };
}

export function useTurnstile(action: string) {
  const { siteKey, required } = turnstileConfig();
  const hostRef = useRef<HTMLDivElement>(null);
  const widgetRef = useRef<string | null>(null);
  const [attempt, setAttempt] = useState(0);
  const [token, setToken] = useState<string | null>(null);
  const [status, setStatus] = useState<TurnstileStatus>(() => siteKey ? 'loading' : required ? 'misconfigured' : 'disabled');

  useEffect(() => {
    if (!siteKey || !hostRef.current) return;
    let active = true;
    void loadTurnstileScript().then(() => {
      if (!active || !hostRef.current || !window.turnstile) return;
      widgetRef.current = window.turnstile.render(hostRef.current, {
        sitekey: siteKey,
        action,
        theme: 'auto',
        language: 'vi',
        size: 'flexible',
        callback: (value) => {
          setToken(value);
          setStatus('verified');
        },
        'error-callback': () => {
          setToken(null);
          setStatus('error');
        },
        'expired-callback': () => {
          setToken(null);
          setStatus('expired');
        },
        'timeout-callback': () => {
          setToken(null);
          setStatus('expired');
        },
      });
      setStatus('pending');
    }).catch(() => {
      if (active) setStatus('error');
    });
    return () => {
      active = false;
      if (widgetRef.current && window.turnstile) window.turnstile.remove(widgetRef.current);
      widgetRef.current = null;
    };
  }, [action, attempt, siteKey]);

  /** Discards the used token and asks for a fresh challenge (or reloads the widget after a load failure). */
  const reset = useCallback(() => {
    setToken(null);
    if (!siteKey) return;
    if (widgetRef.current && window.turnstile) {
      window.turnstile.reset(widgetRef.current);
      setStatus('pending');
    } else {
      setStatus('loading');
      setAttempt((value) => value + 1);
    }
  }, [siteKey]);

  return {
    hostRef,
    enabled: Boolean(siteKey),
    status,
    token,
    /** True when a submit may proceed: a token is present, or the widget is optional and not configured. */
    satisfied: siteKey ? Boolean(token) : !required,
    reset,
  };
}
