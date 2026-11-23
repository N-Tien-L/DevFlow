import { Client, ReconnectionTimeMode } from '@stomp/stompjs';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useAuth } from '../auth/AuthProvider';
import { useNotificationStore } from '../stores/notificationStore';
import type { NotificationResponse } from '../types/notification';

export type NotificationConnectionStatus =
  | 'idle'
  | 'connecting'
  | 'connected'
  | 'reconnecting'
  | 'offline'
  | 'error';

function tokenExpiresAt(token: string): number | null {
  try {
    const segment = token.split('.')[1];
    if (!segment) return null;
    const payload: unknown = JSON.parse(atob(segment.replace(/-/g, '+').replace(/_/g, '/')));
    if (
      typeof payload !== 'object' ||
      payload === null ||
      !('exp' in payload) ||
      typeof payload.exp !== 'number'
    ) {
      return null;
    }
    return payload.exp * 1000;
  } catch {
    return null;
  }
}

export function useNotificationSync(enabled = true) {
  const { user, getValidAccessToken, sessionGeneration, request } = useAuth();
  const {
    addRealtimeNotification,
    fetchUnreadCount,
    fetchNotifications,
    markStale,
    reset,
  } = useNotificationStore();

  const [status, setStatus] = useState<NotificationConnectionStatus>('idle');
  const [retryCount, setRetryCount] = useState(0);
  const clientRef = useRef<Client | null>(null);
  const lastUserIdRef = useRef<string | null>(null);

  const requestRef = useRef(request);
  requestRef.current = request;

  const retry = useCallback(() => {
    setRetryCount((prev) => prev + 1);
  }, []);

  // Reset cached notifications on logout or account switch so data never leaks across users
  useEffect(() => {
    const currentUserId = user?.id ?? null;
    if (lastUserIdRef.current !== null && lastUserIdRef.current !== currentUserId) {
      reset();
    }
    lastUserIdRef.current = currentUserId;
  }, [user?.id, reset]);

  useEffect(() => {
    if (!enabled || !user) {
      setStatus('idle');
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
        try {
          activeToken = await getValidAccessToken({
            minValidityMs: 60_000,
            signal: abortController.signal,
          });
          if (!active || abortController.signal.aborted) return;
          client.connectHeaders = { Authorization: `Bearer ${activeToken}` };
        } catch (reason) {
          if (
            !active ||
            abortController.signal.aborted ||
            (reason instanceof DOMException && reason.name === 'AbortError')
          ) {
            return;
          }
          setStatus('error');
          void client.deactivate({ force: true });
        }
      },
      onConnect: () => {
        if (!active) return;
        const reconnected = everConnected;
        everConnected = true;
        setStatus('connected');

        // Subscribe to user notification queue
        client.subscribe('/user/queue/notifications', (frame) => {
          if (!active) return;
          try {
            const notification: NotificationResponse = JSON.parse(frame.body);
            addRealtimeNotification(notification);
          } catch {
            // Ignore malformed payload
          }
        });

        // Always fetch unread count on connect / reconnect to ensure freshness
        void fetchUnreadCount(requestRef.current);

        // On reconnect: refresh first page if dropdown is open, otherwise mark stale for next open
        if (reconnected) {
          if (useNotificationStore.getState().isDropdownOpen) {
            void fetchNotifications(requestRef.current, 0);
          } else {
            markStale();
          }
        }

        // Schedule proactive token renewal
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
  }, [
    enabled,
    user,
    sessionGeneration,
    getValidAccessToken,
    retryCount,
    addRealtimeNotification,
    fetchUnreadCount,
    fetchNotifications,
    markStale,
  ]);

  return { status, retry };
}
