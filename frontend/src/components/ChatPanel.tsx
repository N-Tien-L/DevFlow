import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useParams, useSearchParams } from 'react-router-dom';
import { getBoard, listBoards, listColumns, listWorkspaces } from '../api/boardApi';
import { useAuth } from '../auth/AuthProvider';
import { useChatWebSocket } from '../hooks/useChatWebSocket';
import { ChatHeader } from './chat/ChatHeader';
import { ChatInput } from './chat/ChatInput';
import { MessageList } from './chat/MessageList';
import type { ColumnResponse, WorkspaceSummary } from '../types/board';
import type { ChatMessage, ProposedTask } from '../types/chat';

function generateUuid(): string {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) {
    return crypto.randomUUID();
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

function formatTime(date: Date = new Date()): string {
  return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

export default function ChatPanel() {
  const [isOpen, setIsOpen] = useState(false);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [conversationId, setConversationId] = useState<string>(() => generateUuid());
  const [isStreaming, setIsStreaming] = useState(false);

  const { boardId: urlBoardId } = useParams();
  const searchParams = useSearchParams()[0];
  const requestedWorkspaceId = searchParams.get('workspace');

  const { user, request, getValidAccessToken } = useAuth();
  const [workspaces, setWorkspaces] = useState<WorkspaceSummary[]>([]);
  const [activeWorkspaceId, setActiveWorkspaceId] = useState<string>('');
  const [columns, setColumns] = useState<ColumnResponse[]>([]);

  const drawerRef = useRef<HTMLDivElement>(null);

  // 1. Load workspaces to determine workspace context
  useEffect(() => {
    if (!user) return;
    let active = true;
    listWorkspaces(request)
      .then((data) => {
        if (!active) return;
        setWorkspaces(data);
        if (data.length > 0) {
          const match = requestedWorkspaceId
            ? data.find((w) => w.id === requestedWorkspaceId)
            : data[0];
          setActiveWorkspaceId(match ? match.id : data[0].id);
        }
      })
      .catch(() => {});
    return () => {
      active = false;
    };
  }, [user, request, requestedWorkspaceId]);

  // 2. Load columns when on a board or when workspace changes
  useEffect(() => {
    if (!user) return;
    let active = true;

    if (urlBoardId) {
      listColumns(request, urlBoardId)
        .then((cols) => {
          if (active) setColumns(cols);
        })
        .catch(() => {});
    } else if (activeWorkspaceId) {
      // Find the first board of this workspace to load columns
      listBoards(request, activeWorkspaceId)
        .then((boardPage) => {
          if (!active || boardPage.items.length === 0) return;
          const firstBoard = boardPage.items[0];
          return getBoard(request, firstBoard.id).then((b) => {
            if (active && b) setColumns(b.columns);
          });
        })
        .catch(() => {});
    }

    return () => {
      active = false;
    };
  }, [user, request, urlBoardId, activeWorkspaceId]);

  // 3. WebSocket Callbacks
  const handleChunk = useCallback((text: string) => {
    setMessages((prev) => {
      const last = prev[prev.length - 1];
      if (!last || last.role !== 'assistant') {
        return [
          ...prev,
          {
            id: generateUuid(),
            role: 'assistant',
            content: text,
            timestamp: formatTime(),
            isStreaming: true,
          },
        ];
      }
      return [
        ...prev.slice(0, -1),
        {
          ...last,
          content: last.content + text,
          isStreaming: true,
        },
      ];
    });
  }, []);

  const handleProposal = useCallback((proposals: ProposedTask[]) => {
    setMessages((prev) => {
      const last = prev[prev.length - 1];
      if (!last || last.role !== 'assistant') {
        return [
          ...prev,
          {
            id: generateUuid(),
            role: 'assistant',
            content: 'Dưới đây là gợi ý chia các task cần làm:',
            timestamp: formatTime(),
            proposals,
            isStreaming: false,
          },
        ];
      }
      return [
        ...prev.slice(0, -1),
        {
          ...last,
          proposals,
          isStreaming: false,
        },
      ];
    });
  }, []);

  const handleEnd = useCallback(() => {
    setIsStreaming(false);
    setMessages((prev) => {
      const last = prev[prev.length - 1];
      if (last && last.role === 'assistant') {
        return [
          ...prev.slice(0, -1),
          { ...last, isStreaming: false },
        ];
      }
      return prev;
    });
  }, []);

  const handleError = useCallback((errorMsg: string) => {
    setIsStreaming(false);
    setMessages((prev) => {
      const last = prev[prev.length - 1];
      if (last && last.role === 'assistant' && !last.content) {
        return [
          ...prev.slice(0, -1),
          {
            ...last,
            content: errorMsg,
            isError: true,
            isStreaming: false,
          },
        ];
      }
      return [
        ...prev,
        {
          id: generateUuid(),
          role: 'assistant',
          content: errorMsg,
          timestamp: formatTime(),
          isError: true,
          isStreaming: false,
        },
      ];
    });
  }, []);

  // 4. WebSocket Hook integration
  const { status: connectionStatus, sendMessage, cancelStream } = useChatWebSocket({
    enabled: Boolean(user && isOpen),
    getValidAccessToken,
    onChunk: handleChunk,
    onProposal: handleProposal,
    onEnd: handleEnd,
    onError: handleError,
  });

  // 5. Send message action
  const handleSend = (text: string) => {
    if (!activeWorkspaceId) {
      handleError('Vui lòng chọn một workspace trước khi gửi tin nhắn.');
      return;
    }

    const userMsg: ChatMessage = {
      id: generateUuid(),
      role: 'user',
      content: text,
      timestamp: formatTime(),
    };

    const assistantPlaceholder: ChatMessage = {
      id: generateUuid(),
      role: 'assistant',
      content: '',
      timestamp: formatTime(),
      isStreaming: true,
    };

    setMessages((prev) => [...prev, userMsg, assistantPlaceholder]);
    setIsStreaming(true);

    const sent = sendMessage({
      conversationId,
      workspaceId: activeWorkspaceId,
      message: text,
    });

    if (!sent) {
      setIsStreaming(false);
      handleError('Không thể gửi tin nhắn. Đang mất kết nối tới máy chủ.');
    }
  };

  const handleCancel = () => {
    cancelStream(conversationId);
    setIsStreaming(false);
    setMessages((prev) => {
      const last = prev[prev.length - 1];
      if (last && last.role === 'assistant') {
        return [
          ...prev.slice(0, -1),
          { ...last, isStreaming: false },
        ];
      }
      return prev;
    });
  };

  const handleNewConversation = () => {
    if (isStreaming) {
      cancelStream(conversationId);
      setIsStreaming(false);
    }
    setConversationId(generateUuid());
    setMessages([]);
  };

  // 6. Keyboard Shortcuts: Esc to close, Ctrl+Shift+C to toggle
  useEffect(() => {
    const handleKeyDown = (e: globalThis.KeyboardEvent) => {
      if (e.key === 'Escape' && isOpen) {
        setIsOpen(false);
      } else if (
        (e.ctrlKey || e.metaKey) &&
        e.shiftKey &&
        (e.key === 'C' || e.key === 'c')
      ) {
        e.preventDefault();
        setIsOpen((prev) => !prev);
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [isOpen]);

  const activeWorkspaceName = useMemo(() => {
    return workspaces.find((w) => w.id === activeWorkspaceId)?.name;
  }, [workspaces, activeWorkspaceId]);

  return (
    <>
      {/* Floating Chat Trigger Button */}
      {!isOpen && (
        <button
          type="button"
          onClick={() => setIsOpen(true)}
          className="fixed bottom-6 right-6 z-40 flex h-13 w-13 items-center justify-center rounded-2xl bg-gradient-to-tr from-indigo-600 to-purple-600 text-white shadow-lg shadow-indigo-600/30 hover:scale-105 hover:shadow-indigo-600/50 active:scale-95 transition-all group"
          aria-label="Mở trợ lý DevFlow AI (Ctrl+Shift+C)"
          title="DevFlow AI (Ctrl+Shift+C)"
        >
          <svg
            className="h-6 w-6 transition-transform group-hover:rotate-12"
            fill="none"
            viewBox="0 0 24 24"
            stroke="currentColor"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M8 10h.01M12 10h.01M16 10h.01M9 16H5a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v8a2 2 0 01-2 2h-5l-5 5v-5z"
            />
          </svg>
        </button>
      )}

      {/* Backdrop for mobile */}
      {isOpen && (
        <div
          className="fixed inset-0 z-50 bg-slate-900/40 backdrop-blur-xs transition-opacity sm:hidden"
          onClick={() => setIsOpen(false)}
          aria-hidden="true"
        />
      )}

      {/* Slide-over Drawer */}
      <div
        ref={drawerRef}
        role="dialog"
        aria-modal="true"
        aria-label="Trợ lý DevFlow AI"
        className={`fixed inset-y-0 right-0 z-50 flex w-full flex-col bg-white shadow-2xl transition-transform duration-300 ease-in-out sm:w-96 sm:max-w-md dark:bg-slate-900 border-l border-slate-200 dark:border-slate-800 ${
          isOpen ? 'translate-x-0' : 'translate-x-full'
        }`}
      >
        <ChatHeader
          workspaceName={activeWorkspaceName}
          connectionStatus={connectionStatus}
          onNewConversation={handleNewConversation}
          onClose={() => setIsOpen(false)}
        />

        <MessageList
          messages={messages}
          columns={columns}
          request={request}
          onSelectPrompt={handleSend}
        />

        <ChatInput
          onSend={handleSend}
          onCancel={handleCancel}
          isStreaming={isStreaming}
          disabled={!activeWorkspaceId}
        />
      </div>
    </>
  );
}
