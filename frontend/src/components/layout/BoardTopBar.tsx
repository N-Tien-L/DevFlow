import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import clsx from 'clsx';
import type { BoardConnectionStatus } from '../../hooks/useBoardSync';
import type { BoardSummary, UserSummary, WorkspaceSummary } from '../../types/board';
import {
  ArchiveIcon,
  BrandMark,
  ChevronDownIcon,
  LayoutGridIcon,
  LogOutIcon,
  PlusIcon,
  WifiOffIcon,
} from '../common/Icons';
import { Avatar, Badge, LoadingSpinner, RoleBadge } from '../common/UiPrimitives';
import { NotificationBell } from '../notification/NotificationBell';

interface BoardTopBarProps {
  user: UserSummary | null;
  workspaces: WorkspaceSummary[];
  workspaceId: string;
  boards: BoardSummary[];
  boardId?: string;
  currentBoardArchived?: boolean;
  connectionStatus: BoardConnectionStatus;
  syncBlocked: boolean;
  onWorkspaceChange(nextWorkspaceId: string): void;
  onBoardChange(nextBoardId: string): void;
  onSelectTask(taskId: string): void;
  onRetrySync(): void;
  onRetryConnection(): void;
  onSignOut(): void;
}

export function BoardTopBar({
  user,
  workspaces,
  workspaceId,
  boards,
  boardId,
  currentBoardArchived,
  connectionStatus,
  syncBlocked,
  onWorkspaceChange,
  onBoardChange,
  onSelectTask,
  onRetrySync,
  onRetryConnection,
  onSignOut,
}: BoardTopBarProps) {
  const [accountOpen, setAccountOpen] = useState(false);
  const accountTriggerRef = useRef<HTMLButtonElement>(null);
  const accountMenuRef = useRef<HTMLDivElement>(null);

  const activeWorkspace = workspaces.find((ws) => ws.id === workspaceId) ?? null;

  useEffect(() => {
    if (!accountOpen) return;
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        setAccountOpen(false);
        accountTriggerRef.current?.focus();
      }
    };
    const handlePointerDown = (event: MouseEvent | TouchEvent) => {
      const target = event.target as Node;
      if (accountTriggerRef.current?.contains(target) || accountMenuRef.current?.contains(target)) {
        return;
      }
      setAccountOpen(false);
    };
    document.addEventListener('keydown', handleKeyDown);
    document.addEventListener('mousedown', handlePointerDown);
    document.addEventListener('touchstart', handlePointerDown);
    return () => {
      document.removeEventListener('keydown', handleKeyDown);
      document.removeEventListener('mousedown', handlePointerDown);
      document.removeEventListener('touchstart', handlePointerDown);
    };
  }, [accountOpen]);

  const syncMeta = (() => {
    if (!boardId) return null;
    if (connectionStatus === 'connected' && !syncBlocked) {
      return {
        label: 'Realtime',
        fullLabel: 'Đồng bộ realtime đang hoạt động · Dữ liệu đã đối soát',
        tone: 'bg-emerald-50 text-emerald-700 ring-emerald-200/90',
        dot: 'bg-emerald-500',
        spin: false,
        offline: false,
        onClick: undefined as (() => void) | undefined,
      };
    }
    if (connectionStatus === 'offline') {
      return {
        label: 'Ngoại tuyến',
        fullLabel: 'Mất kết nối mạng · Board đang ở chế độ chỉ đọc. Nhấn để kết nối lại.',
        tone: 'bg-amber-50 text-amber-800 ring-amber-200/90 hover:bg-amber-100/80',
        dot: 'bg-amber-500',
        spin: false,
        offline: true,
        onClick: onRetryConnection,
      };
    }
    if (connectionStatus === 'error') {
      return {
        label: 'Mất đồng bộ',
        fullLabel: 'Không kết nối được đồng bộ realtime. Nhấn để thử lại.',
        tone: 'bg-rose-50 text-rose-700 ring-rose-200/90 hover:bg-rose-100/80',
        dot: 'bg-rose-500',
        spin: false,
        offline: true,
        onClick: onRetryConnection,
      };
    }
    return {
      label: connectionStatus === 'reconnecting' ? 'Kết nối lại…' : 'Đang đồng bộ…',
      fullLabel: 'Đang đồng bộ và đối soát dữ liệu board mới nhất.',
      tone: 'bg-amber-50 text-amber-800 ring-amber-200/90 hover:bg-amber-100/70',
      dot: 'bg-amber-500',
      spin: true,
      offline: false,
      onClick: connectionStatus === 'connected' && syncBlocked ? onRetrySync : undefined,
    };
  })();

  return (
    <header className="sticky top-0 z-30 border-b border-slate-200/85 bg-white/92 backdrop-blur-xl">
      <div className="mx-auto max-w-[1600px] px-3 sm:px-6 lg:px-8">
        {/* Primary top bar row */}
        <div className="flex min-h-14 items-center justify-between gap-2 sm:gap-3">
          {/* Left: Brand + Desktop Workspace/Board Switchers */}
          <div className="flex min-w-0 flex-1 items-center gap-2 sm:gap-2.5">
            <Link
              to="/boards"
              className="inline-flex min-h-10 shrink-0 items-center gap-2 rounded-xl pr-1 font-display text-sm font-extrabold tracking-tight text-slate-950 transition hover:opacity-90 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:ring-offset-1 sm:text-base"
              aria-label="Trang chủ DevFlow"
            >
              <BrandMark size="sm" />
              <span>DevFlow</span>
            </Link>

            {workspaces.length > 0 && (
              <div className="hidden min-w-0 items-center gap-2 lg:flex">
                <span className="select-none text-slate-300" aria-hidden="true">/</span>
                <div className="relative flex items-center">
                  <label className="sr-only" htmlFor="workspace-picker-desktop">Workspace</label>
                  <select
                    id="workspace-picker-desktop"
                    className="min-h-9 max-w-48 appearance-none rounded-lg border border-slate-200/90 bg-slate-50/90 py-1.5 pl-2.5 pr-8 text-xs font-semibold text-slate-800 transition hover:border-slate-300 hover:bg-white focus:border-indigo-500 focus:bg-white focus:outline-none focus:ring-2 focus:ring-indigo-500/20 xl:max-w-56"
                    value={workspaceId}
                    onChange={(event) => onWorkspaceChange(event.target.value)}
                  >
                    {workspaces.map((workspace) => (
                      <option key={workspace.id} value={workspace.id}>
                        {workspace.name}
                      </option>
                    ))}
                  </select>
                  <ChevronDownIcon className="pointer-events-none absolute right-2 size-3.5 text-slate-500" />
                </div>

                {activeWorkspace && (
                  <RoleBadge role={activeWorkspace.role} className="hidden xl:inline-flex" />
                )}

                {boards.length > 0 && (
                  <>
                    <span className="select-none text-slate-300" aria-hidden="true">/</span>
                    <div className="relative flex items-center">
                      <label className="sr-only" htmlFor="board-picker-desktop">Board</label>
                      <select
                        id="board-picker-desktop"
                        className="min-h-9 max-w-52 appearance-none rounded-lg border border-slate-200/90 bg-slate-50/90 py-1.5 pl-2.5 pr-8 text-xs font-semibold text-slate-800 transition hover:border-slate-300 hover:bg-white focus:border-indigo-500 focus:bg-white focus:outline-none focus:ring-2 focus:ring-indigo-500/20 xl:max-w-64"
                        value={boardId ?? ''}
                        onChange={(event) => onBoardChange(event.target.value)}
                      >
                        <option value="" disabled>Chọn board</option>
                        {boards.map((item) => (
                          <option key={item.id} value={item.id}>
                            {item.name}{item.archived ? ' · Đã lưu trữ' : ''}
                          </option>
                        ))}
                      </select>
                      <ChevronDownIcon className="pointer-events-none absolute right-2 size-3.5 text-slate-500" />
                    </div>
                  </>
                )}

                {currentBoardArchived && (
                  <Badge tone="warning" className="hidden lg:inline-flex">
                    <ArchiveIcon className="size-3" />
                    <span>Lưu trữ</span>
                  </Badge>
                )}
              </div>
            )}
          </div>

          {/* Right: Sync Status Pill + Notifications + Account Menu */}
          <div className="flex shrink-0 items-center gap-1.5 sm:gap-2">
            {syncMeta && (
              syncMeta.onClick ? (
                <button
                  type="button"
                  onClick={syncMeta.onClick}
                  title={syncMeta.fullLabel}
                  aria-label={syncMeta.fullLabel}
                  className={clsx(
                    'inline-flex min-h-9 items-center gap-1.5 rounded-full px-2.5 py-1 text-[11px] font-semibold ring-1 ring-inset transition focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500',
                    syncMeta.tone,
                  )}
                >
                  {syncMeta.spin ? (
                    <LoadingSpinner className="size-3" />
                  ) : syncMeta.offline ? (
                    <WifiOffIcon className="size-3" />
                  ) : (
                    <span className={clsx('size-1.5 rounded-full', syncMeta.dot)} aria-hidden="true" />
                  )}
                  <span className="hidden sm:inline">{syncMeta.label}</span>
                </button>
              ) : (
                <span
                  title={syncMeta.fullLabel}
                  aria-label={syncMeta.fullLabel}
                  className={clsx(
                    'inline-flex min-h-8 items-center gap-1.5 rounded-full px-2.5 py-1 text-[11px] font-semibold ring-1 ring-inset',
                    syncMeta.tone,
                  )}
                >
                  {syncMeta.spin ? (
                    <LoadingSpinner className="size-3" />
                  ) : (
                    <span className="relative flex size-2" aria-hidden="true">
                      <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-emerald-400 opacity-65 motion-reduce:hidden" />
                      <span className={clsx('relative inline-flex size-2 rounded-full', syncMeta.dot)} />
                    </span>
                  )}
                  <span className="hidden sm:inline">{syncMeta.label}</span>
                </span>
              )
            )}

            <NotificationBell onSelectTask={onSelectTask} />

            {/* Account Dropdown */}
            <div className="relative">
              <button
                ref={accountTriggerRef}
                type="button"
                onClick={() => setAccountOpen((open) => !open)}
                aria-expanded={accountOpen}
                aria-haspopup="menu"
                aria-label="Menu tài khoản và workspace"
                className="inline-flex min-h-10 items-center gap-2 rounded-xl border border-transparent px-1.5 py-1 text-left text-sm transition hover:border-slate-200 hover:bg-slate-50 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:ring-offset-1 sm:px-2"
              >
                <Avatar
                  seed={user?.id ?? 'user'}
                  name={user?.fullName || user?.email || 'U'}
                  avatarUrl={user?.avatarUrl}
                  size="md"
                />
                <span className="hidden max-w-32 truncate text-xs font-semibold text-slate-800 lg:inline">
                  {user?.fullName || user?.email}
                </span>
                <ChevronDownIcon className={clsx('hidden size-3.5 text-slate-500 transition sm:block', accountOpen && 'rotate-180')} />
              </button>

              {accountOpen && (
                <div
                  ref={accountMenuRef}
                  role="menu"
                  aria-label="Tùy chọn tài khoản"
                  className="fixed inset-x-3 top-16 z-50 overflow-hidden rounded-2xl border border-slate-200/90 bg-white shadow-xl shadow-slate-900/12 sm:absolute sm:inset-x-auto sm:right-0 sm:top-full sm:mt-2 sm:w-72"
                >
                  <div className="border-b border-slate-100 bg-slate-50/75 px-4 py-3">
                    <div className="flex items-center gap-3">
                      <Avatar
                        seed={user?.id ?? 'user'}
                        name={user?.fullName || user?.email || 'U'}
                        avatarUrl={user?.avatarUrl}
                        size="md"
                      />
                      <div className="min-w-0 flex-1">
                        <p className="truncate text-sm font-bold text-slate-950">
                          {user?.fullName || 'Tài khoản DevFlow'}
                        </p>
                        <p className="truncate text-xs text-slate-600">{user?.email}</p>
                      </div>
                    </div>
                    {activeWorkspace && (
                      <div className="mt-2.5 flex items-center justify-between gap-2 rounded-xl border border-slate-200/80 bg-white px-2.5 py-2 text-xs">
                        <span className="min-w-0 truncate font-semibold text-slate-800">
                          {activeWorkspace.name}
                        </span>
                        <RoleBadge role={activeWorkspace.role} className="shrink-0" />
                      </div>
                    )}
                  </div>

                  <div className="p-1.5">
                    <Link
                      to="/workspaces"
                      role="menuitem"
                      onClick={() => setAccountOpen(false)}
                      className="flex min-h-10 items-center gap-2.5 rounded-xl px-3 py-2 text-sm font-medium text-slate-700 transition hover:bg-slate-100 hover:text-slate-950 focus:bg-slate-100 focus:outline-none"
                    >
                      <LayoutGridIcon className="size-4 text-slate-500" />
                      <span>Tất cả workspace</span>
                    </Link>
                    <Link
                      to="/workspaces/new"
                      role="menuitem"
                      onClick={() => setAccountOpen(false)}
                      className="flex min-h-10 items-center gap-2.5 rounded-xl px-3 py-2 text-sm font-medium text-slate-700 transition hover:bg-slate-100 hover:text-slate-950 focus:bg-slate-100 focus:outline-none"
                    >
                      <PlusIcon className="size-4 text-indigo-600" />
                      <span>Tạo workspace mới</span>
                    </Link>
                  </div>

                  <div className="border-t border-slate-100 p-1.5">
                    <button
                      type="button"
                      role="menuitem"
                      onClick={() => {
                        setAccountOpen(false);
                        onSignOut();
                      }}
                      className="flex min-h-10 w-full items-center gap-2.5 rounded-xl px-3 py-2 text-left text-sm font-medium text-rose-700 transition hover:bg-rose-50 focus:bg-rose-50 focus:outline-none"
                    >
                      <LogOutIcon className="size-4 text-rose-600" />
                      <span>Đăng xuất</span>
                    </button>
                  </div>
                </div>
              )}
            </div>
          </div>
        </div>

        {/* Compact mobile/tablet context switcher strip (< 1024px) */}
        {workspaces.length > 0 && (
          <div className="flex items-center gap-2 border-t border-slate-100 py-2 lg:hidden">
            <div className="relative min-w-0 flex-1">
              <label className="sr-only" htmlFor="workspace-picker-mobile">Workspace</label>
              <select
                id="workspace-picker-mobile"
                className="min-h-10 w-full appearance-none truncate rounded-lg border border-slate-200/90 bg-slate-50 py-1.5 pl-2.5 pr-7 text-xs font-semibold text-slate-800 focus:border-indigo-500 focus:bg-white focus:outline-none focus:ring-2 focus:ring-indigo-500/20"
                value={workspaceId}
                onChange={(event) => onWorkspaceChange(event.target.value)}
              >
                {workspaces.map((workspace) => (
                  <option key={workspace.id} value={workspace.id}>
                    {workspace.name}
                  </option>
                ))}
              </select>
              <ChevronDownIcon className="pointer-events-none absolute right-2 top-1/2 size-3.5 -translate-y-1/2 text-slate-500" />
            </div>

            {boards.length > 0 && (
              <div className="relative min-w-0 flex-1">
                <label className="sr-only" htmlFor="board-picker-mobile">Board</label>
                <select
                  id="board-picker-mobile"
                  className="min-h-10 w-full appearance-none truncate rounded-lg border border-slate-200/90 bg-slate-50 py-1.5 pl-2.5 pr-7 text-xs font-semibold text-slate-800 focus:border-indigo-500 focus:bg-white focus:outline-none focus:ring-2 focus:ring-indigo-500/20"
                  value={boardId ?? ''}
                  onChange={(event) => onBoardChange(event.target.value)}
                >
                  <option value="" disabled>Chọn board</option>
                  {boards.map((item) => (
                    <option key={item.id} value={item.id}>
                      {item.name}{item.archived ? ' · Lưu trữ' : ''}
                    </option>
                  ))}
                </select>
                <ChevronDownIcon className="pointer-events-none absolute right-2 top-1/2 size-3.5 -translate-y-1/2 text-slate-500" />
              </div>
            )}
          </div>
        )}
      </div>
    </header>
  );
}
