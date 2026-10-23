import { Client, ReconnectionTimeMode } from '@stomp/stompjs';
import { useCallback, useEffect, useRef, useState } from 'react';
import { parseBoardMutationMessage, type BoardMutationMessage } from '../realtime/boardMutation';

export type BoardConnectionStatus = 'idle' | 'connecting' | 'connected' | 'reconnecting' | 'offline' | 'error';
const BOARD_ID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

interface UseBoardSyncOptions {
  boardId: string | undefined;
  enabled: boolean;
  sessionGeneration: number;
  getValidAccessToken(options?: { minValidityMs?: number; signal?: AbortSignal }): Promise<string>;
  onMessage(message: BoardMutationMessage): void;
  onMalformedMessage(): void;
  onConnected(reconnected: boolean): void;
  onStatus(status: BoardConnectionStatus): void;
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

export function useBoardSync({
  boardId,
  enabled,
  sessionGeneration,
  getValidAccessToken,
  onMessage,
  onMalformedMessage,
  onConnected,
  onStatus,
}: UseBoardSyncOptions) {
  const [status, setStatus] = useState<BoardConnectionStatus>('idle');
  const [retryGeneration, setRetryGeneration] = useState(0);
  const clientRef = useRef<Client | null>(null);
  const messageRef = useRef(onMessage);
  const malformedRef = useRef(onMalformedMessage);
  const connectedRef = useRef(onConnected);
  const statusRef = useRef(onStatus);

  messageRef.current = onMessage;
  malformedRef.current = onMalformedMessage;
  connectedRef.current = onConnected;
  statusRef.current = onStatus;

  const retry = useCallback(() => setRetryGeneration((value) => value + 1), []);

  useEffect(() => {
    if (!enabled || !boardId) {
      setStatus('idle');
      statusRef.current('idle');
      return undefined;
    }
    if (!BOARD_ID_PATTERN.test(boardId)) {
      setStatus('error');
      statusRef.current('error');
      return undefined;
    }

    let active = true;
    let everConnected = false;
    let activeToken: string | null = null;
    let renewalTimer: number | undefined;
    const abortController = new AbortController();
    const scheme = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const brokerURL = new URL('/ws', window.location.href);
    brokerURL.protocol = scheme;

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
        statusRef.current(everConnected ? 'reconnecting' : 'connecting');
        try {
          activeToken = await getValidAccessToken({ minValidityMs: 60_000, signal: abortController.signal });
          if (!active || abortController.signal.aborted) return;
          client.connectHeaders = { Authorization: `Bearer ${activeToken}` };
        } catch (reason) {
          if (!active || abortController.signal.aborted || reason instanceof DOMException && reason.name === 'AbortError') return;
          setStatus('error');
          statusRef.current('error');
          void client.deactivate({ force: true });
        }
      },
      onConnect: () => {
        if (!active || !boardId) return;
        const reconnected = everConnected;
        everConnected = true;
        setStatus('connected');
        statusRef.current('connected');
        client.subscribe(`/topic/boards/${boardId}`, (frame) => {
          if (!active) return;
          const message = parseBoardMutationMessage(frame.body, boardId);
          if (message) messageRef.current(message);
          else malformedRef.current();
        });
        connectedRef.current(reconnected);

        if (renewalTimer !== undefined) window.clearTimeout(renewalTimer);
        const expiresAt = activeToken ? tokenExpiresAt(activeToken) : null;
        if (expiresAt !== null) {
          const delay = Math.max(1_000, expiresAt - Date.now() - 60_000);
          renewalTimer = window.setTimeout(() => {
            void getValidAccessToken({ minValidityMs: 60_000 }).then(async (token) => {
              if (!active || token === activeToken) return;
              activeToken = token;
              await client.deactivate({ force: true });
              if (active) client.activate();
            }).catch(() => {
              if (!active) return;
              setStatus('error');
              statusRef.current('error');
              void client.deactivate({ force: true });
            });
          }, delay);
        }
      },
      onStompError: () => {
        if (!active) return;
        setStatus('error');
        statusRef.current('error');
        void client.deactivate({ force: true });
      },
      onWebSocketClose: () => {
        if (!active) return;
        const next = navigator.onLine ? 'reconnecting' : 'offline';
        setStatus(next);
        statusRef.current(next);
      },
      onWebSocketError: () => {
        if (!active) return;
        const next = navigator.onLine ? 'reconnecting' : 'offline';
        setStatus(next);
        statusRef.current(next);
      },
      onHeartbeatLost: () => {
        if (!active) return;
        setStatus(navigator.onLine ? 'reconnecting' : 'offline');
        statusRef.current(navigator.onLine ? 'reconnecting' : 'offline');
      },
    });

    clientRef.current = client;
    const handleOnline = () => {
      if (!active) return;
      setStatus('reconnecting');
      statusRef.current('reconnecting');
      client.reconnectDelay = 800 + Math.floor(Math.random() * 401);
      if (client.connected) {
        setStatus('connected');
        statusRef.current('connected');
        connectedRef.current(true);
      }
      client.activate();
    };
    const handleOffline = () => {
      if (!active) return;
      setStatus('offline');
      statusRef.current('offline');
      if (renewalTimer !== undefined) window.clearTimeout(renewalTimer);
      renewalTimer = undefined;
      void client.deactivate({ force: true });
    };
    window.addEventListener('online', handleOnline);
    window.addEventListener('offline', handleOffline);
    setStatus('connecting');
    statusRef.current('connecting');
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
  }, [boardId, enabled, getValidAccessToken, retryGeneration, sessionGeneration]);

  return { status, retry };
}
