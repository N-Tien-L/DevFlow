import { useEffect, useRef, useState } from 'react';
import { Link, useLocation } from 'react-router-dom';
import clsx from 'clsx';
import { useNotificationStore } from '../stores/notificationStore';
import {
  GitBranchIcon,
  KanbanIcon,
  LayoutGridIcon,
  PlusIcon,
  SendIcon,
  SparklesIcon,
  XIcon,
} from './common/Icons';
import { Badge } from './common/UiPrimitives';

export default function ChatPanel() {
  const [isOpen, setIsOpen] = useState(false);
  const launcherRef = useRef<HTMLButtonElement>(null);
  const closeButtonRef = useRef<HTMLButtonElement>(null);
  const location = useLocation();
  const setDropdownOpen = useNotificationStore((state) => state.setDropdownOpen);
  const unreadCount = useNotificationStore((state) => state.unreadCount);

  useEffect(() => {
    if (!isOpen) return;
    requestAnimationFrame(() => closeButtonRef.current?.focus());
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        setIsOpen(false);
        launcherRef.current?.focus();
      }
    };
    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [isOpen]);

  const closePanel = () => {
    setIsOpen(false);
    requestAnimationFrame(() => launcherRef.current?.focus());
  };

  const onBoardRoute = location.pathname.startsWith('/boards');

  return (
    <>
      <button
        ref={launcherRef}
        type="button"
        onClick={() => setIsOpen((open) => !open)}
        aria-expanded={isOpen}
        aria-controls="devflow-ai-chat-panel"
        aria-label={isOpen ? 'Đóng khung trợ lý AI DevFlow' : 'Mở khung trợ lý AI DevFlow'}
        className={clsx(
          'fixed bottom-4 right-4 z-40 inline-flex min-h-11 items-center justify-center gap-2 rounded-full border shadow-lg transition focus:outline-none focus-visible:ring-4 focus-visible:ring-indigo-500/30 sm:bottom-5 sm:right-5',
          isOpen
            ? 'size-11 border-slate-300 bg-white text-slate-700 shadow-slate-900/12 hover:bg-slate-50 sm:w-auto sm:px-4'
            : 'size-11 border-indigo-500/30 bg-slate-900 text-white shadow-slate-900/25 hover:bg-indigo-600 sm:w-auto sm:px-4',
        )}
      >
        {isOpen ? (
          <>
            <XIcon className="size-4" />
            <span className="hidden text-xs font-semibold sm:inline">Đóng</span>
          </>
        ) : (
          <>
            <span className="relative inline-flex items-center justify-center">
              <SparklesIcon className="size-4 text-indigo-300" />
              <span className="absolute -right-1 -top-1 size-2 rounded-full bg-amber-400 ring-2 ring-slate-900" aria-hidden="true" />
            </span>
            <span className="hidden text-xs font-semibold sm:inline">Trợ lý AI</span>
          </>
        )}
      </button>

      {isOpen && (
        <section
          id="devflow-ai-chat-panel"
          role="dialog"
          aria-labelledby="devflow-chat-title"
          aria-describedby="devflow-chat-status"
          className="fixed inset-x-3 bottom-16 z-40 flex max-h-[min(76dvh,540px)] flex-col overflow-hidden rounded-2xl border border-slate-200/90 bg-white shadow-2xl shadow-slate-950/15 sm:inset-x-auto sm:bottom-20 sm:right-5 sm:w-[376px]"
        >
          {/* Header */}
          <header className="flex items-center justify-between gap-2 border-b border-slate-200/80 bg-slate-50/90 px-4 py-3">
            <div className="flex min-w-0 items-center gap-2.5">
              <span className="grid size-8 shrink-0 place-items-center rounded-xl bg-indigo-600 text-white shadow-2xs">
                <SparklesIcon className="size-4" />
              </span>
              <div className="min-w-0">
                <h2 id="devflow-chat-title" className="truncate text-sm font-bold text-slate-950">
                  Trợ lý AI DevFlow
                </h2>
                <div className="mt-0.5 flex items-center gap-1.5">
                  <Badge tone="warning" className="px-2 py-0 text-[10px]">
                    <span className="size-1.5 rounded-full bg-amber-500" aria-hidden="true" />
                    <span>Chưa kết nối dịch vụ AI</span>
                  </Badge>
                </div>
              </div>
            </div>
            <button
              ref={closeButtonRef}
              type="button"
              onClick={closePanel}
              aria-label="Đóng bảng trợ lý AI"
              className="grid size-9 shrink-0 place-items-center rounded-xl text-slate-500 transition hover:bg-slate-200/70 hover:text-slate-900 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500"
            >
              <XIcon className="size-4" />
            </button>
          </header>

          {/* Body */}
          <div className="devflow-scroll flex-1 space-y-3.5 overflow-y-auto p-4">
            <div
              id="devflow-chat-status"
              className="rounded-xl border border-amber-200/90 bg-amber-50/80 p-3 text-xs leading-5 text-amber-950"
              role="status"
            >
              <p className="font-semibold">Dịch vụ hội thoại AI đang chờ kết nối</p>
              <p className="mt-1 text-amber-900/90">
                Luồng chat streaming, phân rã công việc và hỏi đáp dự án sẽ kích hoạt khi tích hợp hoàn tất dịch vụ AI (T-036).
              </p>
            </div>

            <div>
              <p className="mb-2 text-[11px] font-bold uppercase tracking-wider text-slate-500">
                Thao tác khả dụng ngay
              </p>
              <div className="grid grid-cols-1 gap-2">
                {onBoardRoute && (
                  <button
                    type="button"
                    onClick={() => {
                      setIsOpen(false);
                      setDropdownOpen(true);
                    }}
                    className="flex min-h-10 items-center justify-between gap-2 rounded-xl border border-slate-200/90 bg-white px-3 py-2 text-left text-xs font-semibold text-slate-800 transition hover:border-indigo-300 hover:bg-indigo-50/50 hover:text-indigo-700 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500"
                  >
                    <span>Xem thông báo realtime & cảnh báo CI</span>
                    <Badge tone={unreadCount > 0 ? 'danger' : 'neutral'}>
                      {unreadCount > 0 ? `${unreadCount} mới` : 'Mở'}
                    </Badge>
                  </button>
                )}

                <Link
                  to="/workspaces"
                  onClick={() => setIsOpen(false)}
                  className="flex min-h-10 items-center gap-2.5 rounded-xl border border-slate-200/90 bg-white px-3 py-2 text-xs font-semibold text-slate-800 transition hover:border-indigo-300 hover:bg-indigo-50/50 hover:text-indigo-700 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500"
                >
                  <LayoutGridIcon className="size-4 text-slate-500" />
                  <span>Chuyển workspace khác</span>
                </Link>

                <Link
                  to="/workspaces/new"
                  onClick={() => setIsOpen(false)}
                  className="flex min-h-10 items-center gap-2.5 rounded-xl border border-slate-200/90 bg-white px-3 py-2 text-xs font-semibold text-slate-800 transition hover:border-indigo-300 hover:bg-indigo-50/50 hover:text-indigo-700 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500"
                >
                  <PlusIcon className="size-4 text-indigo-600" />
                  <span>Tạo workspace mới</span>
                </Link>

                {!onBoardRoute && (
                  <Link
                    to="/boards"
                    onClick={() => setIsOpen(false)}
                    className="flex min-h-10 items-center gap-2.5 rounded-xl border border-slate-200/90 bg-white px-3 py-2 text-xs font-semibold text-slate-800 transition hover:border-indigo-300 hover:bg-indigo-50/50 hover:text-indigo-700 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500"
                  >
                    <KanbanIcon className="size-4 text-indigo-600" />
                    <span>Đi tới bảng Kanban</span>
                  </Link>
                )}
              </div>
            </div>

            <div className="rounded-xl border border-slate-200/80 bg-slate-50/80 p-3">
              <p className="text-[11px] font-bold uppercase tracking-wider text-slate-500">
                Điểm tích hợp sẵn sàng
              </p>
              <div className="mt-2 flex flex-wrap gap-1.5">
                <Badge tone="brand">
                  <SparklesIcon className="size-3" />
                  <span>T-036 Chat streaming</span>
                </Badge>
                <Badge tone="success">
                  <span>T-040B Thông báo realtime</span>
                </Badge>
                <Badge tone="neutral">
                  <GitBranchIcon className="size-3" />
                  <span>T-026 Git/CI badge</span>
                </Badge>
              </div>
            </div>
          </div>

          {/* Disabled Composer with Clear Status */}
          <div className="border-t border-slate-200/80 bg-slate-50/70 p-3">
            <div className="flex items-center gap-2">
              <label className="sr-only" htmlFor="devflow-chat-input">
                Nhập câu hỏi cho trợ lý AI (tạm khóa khi chưa kết nối dịch vụ AI)
              </label>
              <input
                id="devflow-chat-input"
                type="text"
                disabled
                placeholder="Chat AI tạm khóa (chờ kết nối T-036)…"
                className="min-h-10 min-w-0 flex-1 cursor-not-allowed rounded-xl border border-slate-200 bg-slate-100 px-3 py-2 text-xs text-slate-500 placeholder:text-slate-400"
              />
              <button
                type="button"
                disabled
                aria-label="Gửi tin nhắn (chưa khả dụng)"
                className="grid size-10 shrink-0 cursor-not-allowed place-items-center rounded-xl bg-slate-200 text-slate-400"
              >
                <SendIcon className="size-4" />
              </button>
            </div>
          </div>
        </section>
      )}
    </>
  );
}
