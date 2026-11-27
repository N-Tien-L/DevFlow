import { useCallback, useEffect, useRef, useState, type KeyboardEvent as ReactKeyboardEvent } from 'react';
import { useLocation } from 'react-router-dom';
import clsx from 'clsx';
import { useAuth } from '../auth/AuthProvider';
import { useChatBoardContext } from '../hooks/useChatBoardContext';
import { useChatWebSocket } from '../hooks/useChatWebSocket';
import type { ChatConnectionStatus, ChatFrame, ChatMessage } from '../types/chat';
import { ChatInput } from './chat/ChatInput';
import { applyFrame, chatErrorMessage, createPendingStream, isRetryable, type PendingStream } from './chat/chatStream';
import { MessageList } from './chat/MessageList';
import { RefreshIcon, SparklesIcon, XIcon } from './common/Icons';
import { Badge, type BadgeTone } from './common/UiPrimitives';

/** No frame for this long while waiting for an answer: give up and offer a retry (LLM worst case is ~60 s). */
const ANSWER_TIMEOUT_MS = 90_000;

const STATUS_BADGE: Record<ChatConnectionStatus, { label: string; tone: BadgeTone; dot: string }> = {
  idle: { label: 'Chưa kết nối', tone: 'neutral', dot: 'bg-slate-400' },
  connecting: { label: 'Đang kết nối…', tone: 'warning', dot: 'bg-amber-500 animate-pulse' },
  reconnecting: { label: 'Đang kết nối lại…', tone: 'warning', dot: 'bg-amber-500 animate-pulse' },
  connected: { label: 'Sẵn sàng', tone: 'success', dot: 'bg-emerald-500' },
  offline: { label: 'Mất mạng', tone: 'danger', dot: 'bg-rose-500' },
  error: { label: 'Mất kết nối', tone: 'danger', dot: 'bg-rose-500' },
};

const FOCUSABLE =
  'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])';

function newId() {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID();
  // randomUUID needs a secure context; getRandomValues does not.
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

function timeLabel() {
  return new Date().toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' });
}

export default function ChatPanel() {
  const { user, request, getValidAccessToken } = useAuth();
  const location = useLocation();
  const [isOpen, setIsOpen] = useState(false);
  // Stay connected once opened, so closing the drawer does not drop an answer that is still streaming.
  const [activated, setActivated] = useState(false);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [streaming, setStreaming] = useState(false);

  const pendingRef = useRef<PendingStream | null>(null);
  const timeoutRef = useRef<number | undefined>(undefined);
  const launcherRef = useRef<HTMLButtonElement>(null);
  const panelRef = useRef<HTMLElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const closeButtonRef = useRef<HTMLButtonElement>(null);

  const board = useChatBoardContext(request, isOpen, location.pathname, location.search);

  const patchMessage = useCallback((id: string, patch: Partial<ChatMessage> | ((message: ChatMessage) => Partial<ChatMessage>)) => {
    setMessages((previous) =>
      previous.map((message) =>
        message.id === id ? { ...message, ...(typeof patch === 'function' ? patch(message) : patch) } : message,
      ),
    );
  }, []);

  const clearAnswerTimeout = () => {
    if (timeoutRef.current !== undefined) window.clearTimeout(timeoutRef.current);
    timeoutRef.current = undefined;
  };

  /** Ends the pending answer; frames that still arrive for it are ignored. */
  const finish = useCallback((patch: Partial<ChatMessage>) => {
    const pending = pendingRef.current;
    if (!pending) return;
    pendingRef.current = null;
    clearAnswerTimeout();
    setStreaming(false);
    patchMessage(pending.messageId, patch);
  }, [patchMessage]);

  const fail = useCallback((message: string, retryable = true) => {
    const question = pendingRef.current?.question;
    finish({ status: 'error', content: message, retryText: retryable ? question : undefined });
  }, [finish]);

  const armTimeout = useCallback(() => {
    clearAnswerTimeout();
    timeoutRef.current = window.setTimeout(
      () => fail('Không nhận được phản hồi từ trợ lý. Vui lòng thử lại.'),
      ANSWER_TIMEOUT_MS,
    );
  }, [fail]);

  const handleFrame = useCallback((frame: ChatFrame) => {
    const pending = pendingRef.current;
    const update = applyFrame(pending, frame);
    if (!pending || update.kind === 'ignore') return;
    armTimeout();
    switch (update.kind) {
      case 'text':
        if (update.text) patchMessage(pending.messageId, (message) => ({ content: message.content + update.text }));
        if (update.done) finish({ status: 'done' });
        break;
      case 'proposal':
        patchMessage(pending.messageId, { content: update.content, proposals: update.proposals });
        break;
      case 'cancelled':
        finish({ status: 'cancelled' });
        break;
      case 'error':
        fail(chatErrorMessage(update.code, update.message), isRetryable(update.code));
        break;
    }
  }, [armTimeout, fail, finish, patchMessage]);

  const { status, sendMessage, cancelStream, reconnect } = useChatWebSocket({
    enabled: Boolean(user) && activated,
    getValidAccessToken,
    onFrame: handleFrame,
  });

  // A dropped socket loses the answer in flight: the server does not replay it to the new session.
  useEffect(() => {
    if (status !== 'connected' && pendingRef.current) {
      fail('Mất kết nối khi trợ lý đang trả lời. Hãy thử lại khi đã kết nối.');
    }
  }, [status, fail]);

  useEffect(() => () => clearAnswerTimeout(), []);

  const connected = status === 'connected';
  const canSend = connected && !streaming && Boolean(board.workspaceId);

  const send = (text: string) => {
    const question = text.trim();
    if (!question || !board.workspaceId || streaming) return;
    const conversationId = newId();
    const answerId = newId();
    setMessages((previous) => [
      ...previous,
      { id: newId(), role: 'user', content: question, timestamp: timeLabel() },
      { id: answerId, role: 'assistant', content: '', timestamp: timeLabel(), status: 'streaming' },
    ]);
    pendingRef.current = createPendingStream(conversationId, answerId, question);
    setStreaming(true);
    if (!sendMessage({ conversationId, workspaceId: board.workspaceId, message: question })) {
      fail('Chưa kết nối được tới máy chủ. Hãy thử lại khi đã kết nối.');
      return;
    }
    armTimeout();
  };

  const cancel = () => {
    const pending = pendingRef.current;
    if (!pending) return;
    cancelStream(pending.conversationId);
    finish({ status: 'cancelled' });
  };

  const resetConversation = () => {
    if (pendingRef.current) cancel();
    setMessages([]);
    inputRef.current?.focus();
  };

  const open = () => {
    setActivated(true);
    setIsOpen(true);
  };

  const close = () => {
    setIsOpen(false);
    requestAnimationFrame(() => launcherRef.current?.focus());
  };

  useEffect(() => {
    if (!isOpen) return;
    requestAnimationFrame(() => {
      const input = inputRef.current;
      if (input && !input.disabled) input.focus();
      else closeButtonRef.current?.focus();
    });
  }, [isOpen]);

  // Modal drawer: Escape closes it, Tab stays inside it.
  const handlePanelKeyDown = (event: ReactKeyboardEvent<HTMLElement>) => {
    if (event.key === 'Escape') {
      event.preventDefault();
      event.stopPropagation();
      close();
      return;
    }
    if (event.key !== 'Tab' || !panelRef.current) return;
    const focusable = Array.from(panelRef.current.querySelectorAll<HTMLElement>(FOCUSABLE));
    if (focusable.length === 0) return;
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  };

  const badge = STATUS_BADGE[status];
  const disabledReason = !board.workspaceId
    ? board.loading
      ? 'Đang tải workspace…'
      : 'Hãy tham gia hoặc tạo một workspace để dùng trợ lý.'
    : !connected
      ? 'Đang chờ kết nối tới trợ lý…'
      : undefined;

  return (
    <>
      <button
        ref={launcherRef}
        type="button"
        onClick={() => (isOpen ? close() : open())}
        aria-expanded={isOpen}
        aria-controls="devflow-ai-chat-panel"
        aria-label={isOpen ? 'Đóng khung trợ lý AI DevFlow' : 'Mở khung trợ lý AI DevFlow'}
        className={clsx(
          'fixed bottom-4 right-4 z-40 inline-flex min-h-11 items-center justify-center gap-2 rounded-full border shadow-lg transition focus:outline-none focus-visible:ring-4 focus-visible:ring-indigo-500/30 sm:bottom-5 sm:right-5',
          'size-11 border-indigo-500/30 bg-slate-900 text-white shadow-slate-900/25 hover:bg-indigo-600 sm:w-auto sm:px-4',
          isOpen && 'invisible',
        )}
      >
        <span className="relative inline-flex items-center justify-center">
          <SparklesIcon className="size-4 text-indigo-300" />
          {streaming && (
            <span className="absolute -right-1 -top-1 size-2 animate-pulse rounded-full bg-amber-400 ring-2 ring-slate-900" aria-hidden="true" />
          )}
        </span>
        <span className="hidden text-xs font-semibold sm:inline">Trợ lý AI</span>
      </button>

      <div
        className={clsx(
          'fixed inset-0 z-40 bg-slate-950/25 transition-opacity duration-200',
          isOpen ? 'opacity-100' : 'pointer-events-none opacity-0',
        )}
        onClick={close}
        aria-hidden="true"
      />

      <section
        ref={panelRef}
        id="devflow-ai-chat-panel"
        role="dialog"
        aria-modal="true"
        aria-labelledby="devflow-chat-title"
        aria-describedby="devflow-chat-context"
        inert={!isOpen}
        onKeyDown={handlePanelKeyDown}
        className={clsx(
          'fixed inset-y-0 right-0 z-50 flex w-full flex-col border-l border-slate-200/90 bg-white shadow-2xl shadow-slate-950/20 transition-transform duration-300 ease-out sm:w-[400px]',
          isOpen ? 'translate-x-0' : 'translate-x-full',
        )}
      >
        <header className="flex items-start justify-between gap-2 border-b border-slate-200/80 bg-slate-50/90 px-4 py-3">
          <div className="flex min-w-0 items-start gap-2.5">
            <span className="grid size-8 shrink-0 place-items-center rounded-xl bg-indigo-600 text-white shadow-2xs">
              <SparklesIcon className="size-4" />
            </span>
            <div className="min-w-0">
              <h2 id="devflow-chat-title" className="truncate text-sm font-bold text-slate-950">
                Trợ lý AI DevFlow
              </h2>
              <div className="mt-0.5 flex flex-wrap items-center gap-1.5">
                <Badge tone={badge.tone} className="px-2 py-0 text-[10px]">
                  <span className={clsx('size-1.5 rounded-full', badge.dot)} aria-hidden="true" />
                  <span role="status">{badge.label}</span>
                </Badge>
                {(status === 'error' || status === 'offline') && (
                  <button
                    type="button"
                    onClick={reconnect}
                    className="inline-flex items-center gap-1 rounded-md px-1.5 py-0.5 text-[11px] font-semibold text-indigo-700 hover:bg-indigo-50 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500"
                  >
                    <RefreshIcon className="size-3" />
                    <span>Kết nối lại</span>
                  </button>
                )}
              </div>
              <p id="devflow-chat-context" className="mt-1 truncate text-[11px] text-slate-500">
                {board.workspaceName ? (
                  <>
                    Workspace <span className="font-semibold text-slate-700">{board.workspaceName}</span>
                    {board.boardName && (
                      <>
                        {' · '}Board <span className="font-semibold text-slate-700">{board.boardName}</span>
                      </>
                    )}
                  </>
                ) : board.failed ? (
                  'Không tải được thông tin workspace.'
                ) : (
                  'Hỏi đáp về tiến độ và công việc của dự án.'
                )}
              </p>
            </div>
          </div>
          <div className="flex shrink-0 items-center gap-1">
            <button
              type="button"
              onClick={resetConversation}
              disabled={messages.length === 0}
              aria-label="Bắt đầu cuộc trò chuyện mới"
              title="Cuộc trò chuyện mới"
              className="grid size-9 place-items-center rounded-xl text-slate-500 transition hover:bg-slate-200/70 hover:text-slate-900 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 disabled:cursor-not-allowed disabled:opacity-40"
            >
              <RefreshIcon className="size-4" />
            </button>
            <button
              ref={closeButtonRef}
              type="button"
              onClick={close}
              aria-label="Đóng bảng trợ lý AI"
              className="grid size-9 place-items-center rounded-xl text-slate-500 transition hover:bg-slate-200/70 hover:text-slate-900 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500"
            >
              <XIcon className="size-4" />
            </button>
          </div>
        </header>

        <MessageList
          messages={messages}
          columns={board.columns}
          columnsLoading={board.loading}
          boardName={board.boardName}
          request={request}
          canSend={canSend}
          onPrompt={send}
          onRetry={send}
        />

        <ChatInput
          ref={inputRef}
          onSend={send}
          onCancel={cancel}
          streaming={streaming}
          disabled={!connected || !board.workspaceId}
          disabledReason={disabledReason}
        />
      </section>
    </>
  );
}
