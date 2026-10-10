import { useCallback, useEffect, useRef, useState } from 'react';

interface TurnstileApi {
  render(container: HTMLElement, options: {
    sitekey: string;
    execution: 'execute';
    appearance: 'interaction-only';
    callback(token: string): void;
    'error-callback'(): void;
    'expired-callback'(): void;
  }): string;
  execute(widgetId: string): void;
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

let scriptPromise: Promise<void> | null = null;

function loadTurnstileScript() {
  if (window.turnstile) return Promise.resolve();
  if (scriptPromise) return scriptPromise;
  scriptPromise = new Promise<void>((resolve, reject) => {
    const existing = document.querySelector<HTMLScriptElement>('#cloudflare-turnstile-script');
    const script = existing ?? document.createElement('script');
    const onLoad = () => window.turnstile ? resolve() : reject(new Error('Turnstile không sẵn sàng.'));
    const onError = () => reject(new Error('Không tải được Turnstile.'));
    script.addEventListener('load', onLoad, { once: true });
    script.addEventListener('error', onError, { once: true });
    if (!existing) {
      script.id = 'cloudflare-turnstile-script';
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

export function useTurnstileToken() {
  const siteKey = import.meta.env.VITE_TURNSTILE_SITE_KEY?.trim() ?? '';
  const configuredRequirement = import.meta.env.VITE_TURNSTILE_REQUIRED;
  const required = configuredRequirement === 'true' || (configuredRequirement !== 'false' && import.meta.env.PROD);
  const hostRef = useRef<HTMLDivElement>(null);
  const widgetRef = useRef<string | null>(null);
  const pendingRef = useRef<{ resolve(token: string): void; reject(error: Error): void } | null>(null);
  const [ready, setReady] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!siteKey || !hostRef.current) return;
    let active = true;
    void loadTurnstileScript().then(() => {
      if (!active || !hostRef.current || !window.turnstile) return;
      widgetRef.current = window.turnstile.render(hostRef.current, {
        sitekey: siteKey,
        execution: 'execute',
        appearance: 'interaction-only',
        callback: (token) => {
          pendingRef.current?.resolve(token);
          pendingRef.current = null;
          setError(null);
        },
        'error-callback': () => {
          pendingRef.current?.reject(new Error('Xác minh bảo mật thất bại. Hãy thử lại.'));
          pendingRef.current = null;
          setError('Không xác minh được thao tác. Hãy thử lại.');
        },
        'expired-callback': () => {
          pendingRef.current?.reject(new Error('Mã xác minh đã hết hạn.'));
          pendingRef.current = null;
        },
      });
      setReady(true);
    }).catch(() => setError('Không tải được xác minh bảo mật.'));
    return () => {
      active = false;
      if (pendingRef.current) {
        pendingRef.current.reject(new Error('Đã hủy xác minh.'));
        pendingRef.current = null;
      }
      if (widgetRef.current && window.turnstile) window.turnstile.remove(widgetRef.current);
      widgetRef.current = null;
    };
  }, [siteKey]);

  const getToken = useCallback(() => new Promise<string>((resolve, reject) => {
    const widgetId = widgetRef.current;
    if (!siteKey || !widgetId || !window.turnstile) {
      if (!required) {
        resolve('');
        return;
      }
      reject(new Error(siteKey ? 'Xác minh bảo mật chưa sẵn sàng.' : 'Thiếu cấu hình xác minh bảo mật.'));
      return;
    }
    if (pendingRef.current) {
      reject(new Error('Đang xác minh một thao tác khác.'));
      return;
    }
    pendingRef.current = { resolve, reject };
    window.turnstile.reset(widgetId);
    window.turnstile.execute(widgetId);
    window.setTimeout(() => {
      if (pendingRef.current?.resolve === resolve) {
        pendingRef.current = null;
        reject(new Error('Quá thời gian xác minh. Hãy thử lại.'));
      }
    }, 20_000);
  }), [required, siteKey]);

  return { hostRef, enabled: !required || Boolean(siteKey), ready: !required || ready, error, getToken };
}
