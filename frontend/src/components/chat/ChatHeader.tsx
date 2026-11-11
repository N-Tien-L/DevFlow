import type { ChatConnectionStatus } from '../../types/chat';

interface ChatHeaderProps {
  workspaceName?: string;
  connectionStatus: ChatConnectionStatus;
  onNewConversation: () => void;
  onClose: () => void;
}

export function ChatHeader({
  workspaceName,
  connectionStatus,
  onNewConversation,
  onClose,
}: ChatHeaderProps) {
  const statusConfig = {
    connected: { label: 'Trực tuyến', color: 'bg-emerald-500' },
    connecting: { label: 'Đang kết nối…', color: 'bg-amber-400 animate-pulse' },
    reconnecting: { label: 'Đang kết nối lại…', color: 'bg-amber-500 animate-pulse' },
    offline: { label: 'Ngoại tuyến', color: 'bg-slate-400' },
    error: { label: 'Mất kết nối', color: 'bg-rose-500' },
    idle: { label: 'Chờ', color: 'bg-slate-300' },
  }[connectionStatus];

  return (
    <div className="flex items-center justify-between border-b border-slate-200 bg-white px-4 py-3 dark:border-slate-800 dark:bg-slate-900">
      <div className="flex items-center gap-2.5">
        <div className="flex h-8 w-8 items-center justify-center rounded-xl bg-gradient-to-tr from-indigo-600 to-purple-600 text-white shadow-xs">
          <svg className="h-4.5 w-4.5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 10V3L4 14h7v7l9-11h-7z" />
          </svg>
        </div>

        <div>
          <div className="flex items-center gap-2">
            <h3 className="text-sm font-bold text-slate-900 dark:text-slate-100">DevFlow AI</h3>
            <span className="flex items-center gap-1 rounded-full bg-slate-100 px-2 py-0.5 text-3xs font-medium text-slate-600 dark:bg-slate-800 dark:text-slate-300">
              <span className={`h-1.5 w-1.5 rounded-full ${statusConfig.color}`} />
              <span>{statusConfig.label}</span>
            </span>
          </div>

          {workspaceName && (
            <p className="text-3xs text-slate-400 dark:text-slate-500 truncate max-w-[180px]">
              Không gian: <span className="font-medium text-slate-600 dark:text-slate-400">{workspaceName}</span>
            </p>
          )}
        </div>
      </div>

      <div className="flex items-center gap-1">
        <button
          type="button"
          onClick={onNewConversation}
          title="Bắt đầu đoạn chat mới"
          className="rounded-lg p-1.5 text-slate-400 hover:bg-slate-100 hover:text-slate-700 transition dark:hover:bg-slate-800 dark:hover:text-slate-200"
          aria-label="Đoạn chat mới"
        >
          <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
          </svg>
        </button>

        <button
          type="button"
          onClick={onClose}
          title="Đóng (Esc)"
          className="rounded-lg p-1.5 text-slate-400 hover:bg-slate-100 hover:text-slate-700 transition dark:hover:bg-slate-800 dark:hover:text-slate-200"
          aria-label="Đóng bảng chat"
        >
          <svg className="h-4.5 w-4.5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
          </svg>
        </button>
      </div>
    </div>
  );
}
