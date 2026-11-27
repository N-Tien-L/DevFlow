import { Client, ReconnectionTimeMode } from '@stomp/stompjs';
import { useCallback, useEffect, useRef, useState } from 'react';
import { parseChatFrame } from '../components/chat/chatStream';
import type { ChatConnectionStatus, ChatFrame, ChatMessageRequest } from '../types/chat';

interface UseChatWebSocketOptions {
  enabled: boolean;
  getValidAccessToken: (options?: { minValidityMs?: number; signal?: AbortSignal }) => Promise<string>;
  onFrame: (frame: ChatFrame) => void;
}

function tokenExpiresAt(token: string): number | null {
  try {
    const segment = token.split('.')[1];
    if (!segment) return null;
    const payload: unknown = JSON.parse(atob(segment.replace(/-/g, '+').replace(/_/g, '/')));
    if (typeof payload !== 'object' || payload === null || !('exp' in payload) || typeof payload.exp !== 'number') return null;
    return payload.exp * 1000;
  } catch {
    return null;
  }
}

/**
 * STOMP transport for the AI chat: subscribes to the caller's own `/user/queue/chat`, publishes to
 * `/app/chat` and `/app/chat/cancel`, renews the JWT before it expires and reconnects with backoff.
 */
export function useChatWebSocket({ enabled, getValidAccessToken, onFrame }: UseChatWebSocketOptions) {
  const [status, setStatus] = useState<ChatConnectionStatus>('idle');
  const [generation, setGeneration] = useState(0);
  const clientRef = useRef<Client | null>(null);
  const frameRef = useRef(onFrame);
  frameRef.current = onFrame;

  useEffect(() => {
    if (!enabled) {
      setStatus('idle');
      return undefined;
    }

    let active = true;
    let everConnected = false;
    let activeToken: string | null = null;
    let renewalTimer: number | undefined;
    const abortController = new AbortController();

    const brokerURL = new URL('/ws', window.location.href);
    brokerURL.protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';

    const client = new Client({
      brokerURL: brokerURL.toString(),
      reconnectDelay: 800 + Math.floor(Math.random() * 401),
      reconnectTimeMode: ReconnectionTimeMode.EXPONENTIAL,
      maxReconnectDelay: 30_000,
      connectionTimeout: 10_000,
      heartbeatIncoming: 10_000,
      heartbeatOutgoing: 10_000,
      discardWebsocketOnCommFailure: true,
      debug: () => undefined,
      beforeConnect: async () => {
        if (!active || abortController.signal.aborted) return;
        setStatus(everConnected ? 'reconnecting' : 'connecting');
        try {
          activeToken = await getValidAccessToken({ minValidityMs: 60_000, signal: abortController.signal });
          if (!active || abortController.signal.aborted) return;
          client.connectHeaders = { Authorization: `Bearer ${activeToken}` };
        } catch (reason) {
          if (!active || abortController.signal.aborted || (reason instanceof DOMException && reason.name === 'AbortError')) return;
          setStatus('error');
          void client.deactivate({ force: true });
        }
      },
      onConnect: () => {
        if (!active) return;
        everConnected = true;
        setStatus('connected');

        client.subscribe('/user/queue/chat', (message) => {
          if (!active) return;
          const frame = parseChatFrame(message.body);
          if (frame) frameRef.current(frame);
        });

        if (renewalTimer !== undefined) window.clearTimeout(renewalTimer);
        const expiresAt = activeToken ? tokenExpiresAt(activeToken) : null;
        if (expiresAt !== null) {
          const delay = Math.max(1_000, expiresAt - Date.now() - 60_000);
          renewalTimer = window.setTimeout(() => {
            void getValidAccessToken({ minValidityMs: 60_000 })
              .then(async (token) => {
                if (!active || token === activeToken) return;
                activeToken = token;
                await client.deactivate({ force: true });
                if (active) client.activate();
              })
              .catch(() => {
                if (!active) return;
                setStatus('error');
                void client.deactivate({ force: true });
              });
          }, delay);
        }
      },
      onStompError: () => {
        if (!active) return;
        setStatus('error');
        void client.deactivate({ force: true });
      },
      onWebSocketClose: () => {
        if (!active) return;
        setStatus(navigator.onLine ? 'reconnecting' : 'offline');
      },
      onWebSocketError: () => {
        if (!active) return;
        setStatus(navigator.onLine ? 'reconnecting' : 'offline');
      },
      onHeartbeatLost: () => {
        if (!active) return;
        setStatus(navigator.onLine ? 'reconnecting' : 'offline');
      },
    });

    clientRef.current = client;

    const handleOnline = () => {
      if (!active) return;
      setStatus('reconnecting');
      client.reconnectDelay = 800 + Math.floor(Math.random() * 401);
      client.activate();
    };

    const handleOffline = () => {
      if (!active) return;
      setStatus('offline');
      if (renewalTimer !== undefined) window.clearTimeout(renewalTimer);
      renewalTimer = undefined;
      void client.deactivate({ force: true });
    };

    window.addEventListener('online', handleOnline);
    window.addEventListener('offline', handleOffline);

    setStatus('connecting');
    client.activate();

    return () => {
      active = false;
      abortController.abort();
      if (renewalTimer !== undefined) window.clearTimeout(renewalTimer);
      window.removeEventListener('online', handleOnline);
      window.removeEventListener('offline', handleOffline);
      if (clientRef.current === client) clientRef.current = null;
      void client.deactivate({ force: true });
    };
  }, [enabled, generation, getValidAccessToken]);

  const sendMessage = useCallback((request: ChatMessageRequest) => {
    const client = clientRef.current;
    if (!client || !client.connected) return false;
    client.publish({ destination: '/app/chat', body: JSON.stringify(request) });
    return true;
  }, []);

  const cancelStream = useCallback((conversationId: string) => {
    const client = clientRef.current;
    if (!client || !client.connected) return;
    client.publish({ destination: '/app/chat/cancel', body: JSON.stringify({ conversationId }) });
  }, []);

  /** Drops the current socket and connects again (manual retry after an error). */
  const reconnect = useCallback(() => setGeneration((value) => value + 1), []);

  return { status, sendMessage, cancelStream, reconnect };
}
