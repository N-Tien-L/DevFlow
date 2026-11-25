import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { listWorkspaces } from '../api/boardApi';
import { useAuth } from '../auth/AuthProvider';
import { authErrorMessage } from '../auth/authErrorMessage';
import { AuthLayout } from '../components/auth/AuthLayout';
import { FormAlert, SecondaryButton, linkClass } from '../components/auth/FormControls';
import {
  ArrowRightIcon,
  LayoutGridIcon,
  LogOutIcon,
  PlusIcon,
  RefreshIcon,
  SearchIcon,
} from '../components/common/Icons';
import { Avatar, EmptyStatePanel, RoleBadge } from '../components/common/UiPrimitives';
import type { WorkspaceSummary } from '../types/board';

type State =
  | { kind: 'loading' }
  | { kind: 'error'; message: string }
  | { kind: 'ready'; workspaces: WorkspaceSummary[] };

function workspaceBoardsPath(workspaceId: string) {
  return `/boards?workspace=${encodeURIComponent(workspaceId)}`;
}

export default function WorkspaceSelectionPage() {
  const { request, user, signOut } = useAuth();
  const [state, setState] = useState<State>({ kind: 'loading' });
  const [attempt, setAttempt] = useState(0);
  const [query, setQuery] = useState('');

  useEffect(() => {
    let active = true;
    listWorkspaces(request).then((workspaces) => {
      if (active) {
        setState({
          kind: 'ready',
          workspaces: [...workspaces].sort((left, right) => left.name.localeCompare(right.name, 'vi')),
        });
      }
    }).catch((reason: unknown) => {
      if (active) setState({ kind: 'error', message: authErrorMessage(reason, 'Không tải được danh sách workspace.') });
    });
    return () => { active = false; };
  }, [attempt, request]);

  const retry = useCallback(() => {
    setState({ kind: 'loading' });
    setAttempt((value) => value + 1);
  }, []);

  const filteredWorkspaces = useMemo(() => {
    if (state.kind !== 'ready') return [];
    const normalized = query.trim().toLocaleLowerCase();
    if (!normalized) return state.workspaces;
    return state.workspaces.filter((ws) =>
      ws.name.toLocaleLowerCase().includes(normalized) ||
      ws.slug.toLocaleLowerCase().includes(normalized),
    );
  }, [query, state]);

  return (
    <AuthLayout
      wide
      title="Chọn workspace"
      description={user ? (
        <div className="mt-1 inline-flex max-w-full items-center gap-2 rounded-full border border-slate-200/80 bg-slate-50 px-2.5 py-1 text-xs text-slate-600 dark:border-slate-800 dark:bg-slate-950/70 dark:text-slate-300">
          <Avatar seed={user.id} name={user.fullName || user.email} avatarUrl={user.avatarUrl} size="xs" />
          <span className="truncate font-medium text-slate-800 dark:text-slate-100">{user.fullName || user.email}</span>
          {user.fullName && <span className="hidden truncate text-slate-500 sm:inline">· {user.email}</span>}
        </div>
      ) : undefined}
      headerAction={(
        <Link
          to="/workspaces/new"
          className="inline-flex min-h-10 items-center gap-1.5 rounded-xl bg-indigo-600 px-3.5 py-2 text-xs font-semibold text-white shadow-xs shadow-indigo-600/20 transition hover:bg-indigo-700 focus:outline-none focus-visible:ring-4 focus-visible:ring-indigo-500/30 sm:text-sm"
        >
          <PlusIcon className="size-4" />
          <span>Tạo workspace</span>
        </Link>
      )}
      footer={(
        <div className="flex flex-wrap items-center justify-between gap-3">
          <Link className={linkClass} to="/workspaces/new">Tạo workspace mới cho nhóm</Link>
          <button
            type="button"
            className="inline-flex min-h-9 items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-sm font-semibold text-slate-600 transition hover:bg-slate-100 hover:text-slate-900 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 dark:text-slate-300 dark:hover:bg-slate-800 dark:hover:text-white"
            onClick={signOut}
          >
            <LogOutIcon className="size-4" />
            <span>Đăng xuất</span>
          </button>
        </div>
      )}
    >
      {state.kind === 'loading' && (
        <div role="status" aria-label="Đang tải workspace">
          <ul className="grid grid-cols-1 gap-3 sm:grid-cols-2" aria-hidden="true">
            {[0, 1, 2, 3].map((item) => (
              <li key={item} className="h-[88px] animate-pulse rounded-2xl border border-slate-200/80 bg-slate-100/80 motion-reduce:animate-none dark:border-slate-800 dark:bg-slate-800/70" />
            ))}
          </ul>
        </div>
      )}

      {state.kind === 'error' && (
        <div className="space-y-4">
          <FormAlert tone="error">{state.message}</FormAlert>
          <SecondaryButton onClick={retry}>
            <RefreshIcon className="size-4" />
            <span>Thử tải lại</span>
          </SecondaryButton>
        </div>
      )}

      {state.kind === 'ready' && state.workspaces.length === 0 && (
        <EmptyStatePanel
          icon={<LayoutGridIcon className="size-6" />}
          eyebrow="Bắt đầu với DevFlow"
          title="Bạn chưa thuộc workspace nào"
          description="Tạo workspace mới để quản lý board Kanban và kết nối Git/CI, hoặc mở liên kết lời mời từ quản trị viên của nhóm."
          actions={(
            <Link
              to="/workspaces/new"
              className="inline-flex min-h-11 items-center gap-2 rounded-xl bg-indigo-600 px-5 py-2.5 text-sm font-semibold text-white shadow-sm shadow-indigo-600/25 transition hover:bg-indigo-700 focus:outline-none focus-visible:ring-4 focus-visible:ring-indigo-500/30"
            >
              <PlusIcon className="size-4" />
              <span>Tạo workspace đầu tiên</span>
            </Link>
          )}
        />
      )}

      {state.kind === 'ready' && state.workspaces.length > 0 && (
        <div className="space-y-4">
          {state.workspaces.length >= 4 && (
            <label className="relative block">
              <span className="sr-only">Tìm workspace theo tên hoặc mã</span>
              <SearchIcon className="pointer-events-none absolute left-3.5 top-1/2 size-4 -translate-y-1/2 text-slate-400" />
              <input
                type="search"
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                placeholder="Tìm nhanh workspace…"
                className="min-h-11 w-full rounded-xl border border-slate-200 bg-slate-50/80 py-2.5 pl-10 pr-4 text-base text-slate-900 outline-none transition placeholder:text-slate-400 focus:border-indigo-500 focus:bg-white focus:ring-4 focus:ring-indigo-500/15 sm:text-sm dark:border-slate-800 dark:bg-slate-950 dark:text-slate-100"
              />
            </label>
          )}

          {filteredWorkspaces.length === 0 ? (
            <div className="rounded-2xl border border-dashed border-slate-300 bg-slate-50/70 px-4 py-8 text-center text-sm text-slate-600 dark:border-slate-700 dark:bg-slate-950/60 dark:text-slate-400">
              <p>Không tìm thấy workspace phù hợp với “{query.trim()}”.</p>
              <button type="button" onClick={() => setQuery('')} className={`${linkClass} mt-2 inline-block text-xs`}>
                Xóa từ khóa tìm kiếm
              </button>
            </div>
          ) : (
            <ul className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              {filteredWorkspaces.map((workspace) => (
                <li key={workspace.id} className="min-w-0">
                  <Link
                    to={workspaceBoardsPath(workspace.id)}
                    className="group flex h-full min-w-0 items-center gap-3.5 rounded-2xl border border-slate-200/90 bg-white p-4 shadow-2xs transition hover:-translate-y-0.5 hover:border-indigo-300 hover:bg-indigo-50/35 hover:shadow-md hover:shadow-indigo-950/5 focus:outline-none focus-visible:ring-4 focus-visible:ring-indigo-500/25 motion-reduce:hover:translate-y-0 dark:border-slate-800 dark:bg-slate-950/50 dark:hover:border-indigo-500/50 dark:hover:bg-indigo-500/10"
                  >
                    <Avatar seed={workspace.id} name={workspace.name} size="lg" className="rounded-xl" />
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-sm font-bold text-slate-950 group-hover:text-indigo-700 dark:text-white dark:group-hover:text-indigo-300">
                        {workspace.name}
                      </span>
                      <span className="mt-1 flex flex-wrap items-center gap-1.5">
                        <span className="font-mono-code max-w-full truncate rounded bg-slate-100 px-1.5 py-0.5 text-[11px] text-slate-600 dark:bg-slate-800 dark:text-slate-300">
                          {workspace.slug}
                        </span>
                        <RoleBadge role={workspace.role} />
                      </span>
                    </span>
                    <ArrowRightIcon className="size-4 shrink-0 text-slate-400 transition group-hover:translate-x-0.5 group-hover:text-indigo-600 motion-reduce:group-hover:translate-x-0 dark:text-slate-500 dark:group-hover:text-indigo-400" />
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </AuthLayout>
  );
}
