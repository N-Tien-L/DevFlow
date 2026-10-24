import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { listWorkspaces } from '../api/boardApi';
import { useAuth } from '../auth/AuthProvider';
import { authErrorMessage } from '../auth/authErrorMessage';
import { workspaceRoleLabel } from '../auth/workspaceRole';
import { AuthLayout } from '../components/auth/AuthLayout';
import { FormAlert, SecondaryButton, linkClass } from '../components/auth/FormControls';
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

  useEffect(() => {
    let active = true;
    listWorkspaces(request).then((workspaces) => {
      if (active) setState({ kind: 'ready', workspaces: [...workspaces].sort((left, right) => left.name.localeCompare(right.name, 'vi')) });
    }).catch((reason: unknown) => {
      if (active) setState({ kind: 'error', message: authErrorMessage(reason, 'Không tải được danh sách workspace.') });
    });
    return () => { active = false; };
  }, [attempt, request]);

  const retry = useCallback(() => {
    setState({ kind: 'loading' });
    setAttempt((value) => value + 1);
  }, []);

  return (
    <AuthLayout
      wide
      title="Chọn workspace"
      description={user ? <>Đăng nhập với <span className="break-all font-medium text-slate-700 dark:text-slate-200">{user.email}</span></> : undefined}
      footer={(
        <div className="flex flex-wrap items-center justify-between gap-3">
          <Link className={linkClass} to="/workspaces/new">Tạo workspace mới</Link>
          <button type="button" className={linkClass} onClick={signOut}>Đăng xuất</button>
        </div>
      )}
    >
      {state.kind === 'loading' && (
        <div role="status" aria-label="Đang tải workspace">
          <ul className="grid gap-3 sm:grid-cols-2" aria-hidden="true">
            {[0, 1, 2, 3].map((item) => <li key={item} className="h-24 animate-pulse rounded-2xl bg-slate-100 motion-reduce:animate-none dark:bg-slate-800" />)}
          </ul>
        </div>
      )}

      {state.kind === 'error' && (
        <div className="space-y-4">
          <FormAlert tone="error">{state.message}</FormAlert>
          <SecondaryButton onClick={retry}>Thử tải lại</SecondaryButton>
        </div>
      )}

      {state.kind === 'ready' && state.workspaces.length === 0 && (
        <div className="rounded-2xl border border-dashed border-slate-300 px-6 py-10 text-center dark:border-slate-700">
          <h2 className="text-lg font-semibold text-slate-900 dark:text-white">Bạn chưa thuộc workspace nào</h2>
          <p className="mx-auto mt-2 max-w-sm text-sm leading-6 text-slate-500 dark:text-slate-400">Tạo workspace cho nhóm của bạn, hoặc nhờ quản trị viên gửi lời mời tham gia workspace có sẵn.</p>
          <Link
            to="/workspaces/new"
            className="mt-6 inline-flex rounded-xl bg-indigo-600 px-5 py-3 text-sm font-semibold text-white transition hover:bg-indigo-700 focus:outline-none focus-visible:ring-4 focus-visible:ring-indigo-300"
          >
            Tạo workspace
          </Link>
        </div>
      )}

      {state.kind === 'ready' && state.workspaces.length > 0 && (
        <ul className="grid gap-3 sm:grid-cols-2">
          {state.workspaces.map((workspace) => (
            <li key={workspace.id}>
              <Link
                to={workspaceBoardsPath(workspace.id)}
                className="group flex h-full items-center gap-4 rounded-2xl border border-slate-200 p-4 transition hover:border-indigo-300 hover:bg-indigo-50/50 focus:outline-none focus-visible:ring-4 focus-visible:ring-indigo-200 dark:border-slate-800 dark:hover:border-indigo-500/50 dark:hover:bg-indigo-500/5 dark:focus-visible:ring-indigo-500/30"
              >
                <span className="grid size-11 shrink-0 place-items-center rounded-xl bg-indigo-100 text-base font-bold uppercase text-indigo-700 dark:bg-indigo-500/15 dark:text-indigo-300" aria-hidden="true">
                  {workspace.name.trim().charAt(0) || '?'}
                </span>
                <span className="min-w-0 flex-1">
                  <span className="block truncate font-semibold text-slate-900 dark:text-white">{workspace.name}</span>
                  <span className="mt-0.5 block truncate text-xs text-slate-500 dark:text-slate-400">{workspace.slug}</span>
                </span>
                <span className="shrink-0 rounded-full bg-slate-100 px-2.5 py-1 text-xs font-medium text-slate-600 dark:bg-slate-800 dark:text-slate-300">
                  {workspaceRoleLabel(workspace.role)}
                </span>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </AuthLayout>
  );
}
