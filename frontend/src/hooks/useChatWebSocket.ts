import { Client, ReconnectionTimeMode } from '@stomp/stompjs';
import { useCallback, useEffect, useRef, useState } from 'react';
import type { ChatConnectionStatus, ChatMessageRequest, ChatMessageResponse, ProposedTask } from '../types/chat';

interface UseChatWebSocketOptions {
  enabled: boolean;
  getValidAccessToken: (options?: { minValidityMs?: number; signal?: AbortSignal }) => Promise<string>;
  onChunk: (text: string) => void;
  onProposal: (proposals: ProposedTask[]) => void;
  onEnd: () => void;
  onError: (errorMsg: string) => void;
  onStatusChange?: (status: ChatConnectionStatus) => void;
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

export function useChatWebSocket({
  enabled,
  getValidAccessToken,
  onChunk,
  onProposal,
  onEnd,
  onError,
  onStatusChange,
}: UseChatWebSocketOptions) {
  const [status, setStatus] = useState<ChatConnectionStatus>('idle');
  const clientRef = useRef<Client | null>(null);

  const chunkRef = useRef(onChunk);
  const proposalRef = useRef(onProposal);
  const endRef = useRef(onEnd);
  const errorRef = useRef(onError);
  const statusRef = useRef(onStatusChange);

  chunkRef.current = onChunk;
  proposalRef.current = onProposal;
  endRef.current = onEnd;
  errorRef.current = onError;
  statusRef.current = onStatusChange;

  const updateStatus = useCallback((next: ChatConnectionStatus) => {
    setStatus(next);
    statusRef.current?.(next);
  }, []);

  useEffect(() => {
    if (!enabled) {
      updateStatus('idle');
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
      beforeConnect: async () => {
        if (!active || abortController.signal.aborted) return;
        updateStatus(everConnected ? 'reconnecting' : 'connecting');
        try {
          activeToken = await getValidAccessToken({ minValidityMs: 60_000, signal: abortController.signal });
          if (!active || abortController.signal.aborted) return;
          client.connectHeaders = { Authorization: `Bearer ${activeToken}` };
        } catch (reason) {
          if (!active || abortController.signal.aborted || (reason instanceof DOMException && reason.name === 'AbortError')) return;
          updateStatus('error');
          void client.deactivate({ force: true });
        }
      },
      onConnect: () => {
        if (!active) return;
        everConnected = true;
        updateStatus('connected');

        // Subscribe to user private chat queue
        client.subscribe('/user/queue/chat', (frame) => {
          if (!active) return;
          try {
            const data: ChatMessageResponse = JSON.parse(frame.body);
            if (data.type === 'CHUNK') {
              chunkRef.current(data.content);
            } else if (data.type === 'TASK_PROPOSAL') {
              const proposals = (data.metadata?.proposals as ProposedTask[]) ?? [];
              proposalRef.current(proposals);
            } else if (data.type === 'END') {
              endRef.current();
            } else if (data.type === 'ERROR') {
              errorRef.current(data.content || 'An error occurred.');
            }
          } catch {
            errorRef.current('Malformed chat message received.');
          }
        });

        // Token renewal before expiry
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
                updateStatus('error');
                void client.deactivate({ force: true });
              });
          }, delay);
        }
      },
      onStompError: () => {
        if (!active) return;
        updateStatus('error');
        void client.deactivate({ force: true });
      },
      onWebSocketClose: () => {
        if (!active) return;
        updateStatus(navigator.onLine ? 'reconnecting' : 'offline');
      },
      onWebSocketError: () => {
        if (!active) return;
        updateStatus(navigator.onLine ? 'reconnecting' : 'offline');
      },
      onHeartbeatLost: () => {
        if (!active) return;
        updateStatus(navigator.onLine ? 'reconnecting' : 'offline');
      },
    });

    clientRef.current = client;

    const handleOnline = () => {
      if (!active) return;
      updateStatus('reconnecting');
      if (client.connected) {
        updateStatus('connected');
      }
      client.activate();
    };

    const handleOffline = () => {
      if (!active) return;
      updateStatus('offline');
      if (renewalTimer !== undefined) window.clearTimeout(renewalTimer);
      renewalTimer = undefined;
      void client.deactivate({ force: true });
    };

    window.addEventListener('online', handleOnline);
    window.addEventListener('offline', handleOffline);

    updateStatus('connecting');
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
  }, [enabled, getValidAccessToken, updateStatus]);

  const sendMessage = useCallback((req: ChatMessageRequest) => {
    const client = clientRef.current;
    if (!client || !client.connected) {
      return false;
    }
    client.publish({
      destination: '/app/chat',
      body: JSON.stringify(req),
    });
    return true;
  }, []);

  const cancelStream = useCallback((conversationId: string) => {
    const client = clientRef.current;
    if (!client || !client.connected) return;
    client.publish({
      destination: '/app/chat/cancel',
      body: JSON.stringify({ conversationId }),
    });
  }, []);

  return {
    status,
    sendMessage,
    cancelStream,
  };
}
