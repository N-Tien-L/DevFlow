import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { acceptInvitation, listWorkspaces } from '../api/boardApi';
import { useAuth } from '../auth/AuthProvider';
import { captureInvitationToken, clearInvitationToken, invitationFailure, isFinalFailure, type InvitationFailure } from '../auth/invitation';
import { loginPathWithReturnTo } from '../auth/returnTo';
import { workspaceRoleLabel } from '../auth/workspaceRole';
import { AuthLayout } from '../components/auth/AuthLayout';
import { FormAlert, SecondaryButton, linkClass } from '../components/auth/FormControls';
import { useCountdown } from '../hooks/useCountdown';
import type { WorkspaceSummary } from '../types/board';

const ACCEPT_PATH = '/invitations/accept';

const primaryClass = 'block w-full rounded-xl bg-indigo-600 px-4 py-3 text-center font-semibold text-white shadow-lg shadow-indigo-600/20 transition hover:bg-indigo-700 focus:outline-none focus-visible:ring-4 focus-visible:ring-indigo-300 disabled:cursor-not-allowed disabled:opacity-60 dark:shadow-none dark:focus-visible:ring-indigo-500/40';

const secondaryClass = 'block w-full rounded-xl border border-slate-300 px-4 py-3 text-center font-semibold text-slate-700 transition hover:bg-slate-50 focus:outline-none focus-visible:ring-4 focus-visible:ring-indigo-200 dark:border-slate-700 dark:text-slate-200 dark:hover:bg-slate-800 dark:focus-visible:ring-indigo-500/30';

interface Accepted {
  workspace: WorkspaceSummary;
  /** The caller was already a member; the server kept their existing role. */
  alreadyMember: boolean;
}

function failureMessage(failure: InvitationFailure, email: string | undefined) {
  switch (failure.kind) {
    case 'invalid':
      return 'Lời mời không hợp lệ hoặc đã được sử dụng.';
    case 'expired':
      return 'Lời mời đã hết hạn, hãy nhờ quản trị viên gửi lại.';
    case 'emailMismatch':
      return `Lời mời này được gửi tới một địa chỉ email khác với tài khoản bạn đang đăng nhập${email ? ` (${email})` : ''}. Hãy đăng xuất rồi đăng nhập bằng đúng tài khoản được mời.`;
    case 'rateLimited':
      return 'Bạn thử quá nhiều lần. Vui lòng đợi rồi thử lại.';
    case 'uncertain':
      return 'Chưa rõ lời mời đã được chấp nhận hay chưa do không nhận được phản hồi từ máy chủ.';
    case 'failed':
      return 'Không chấp nhận được lời mời. Vui lòng thử lại.';
  }
}

export default function InvitationAcceptPage() {
  const { user, ready, request, signOut } = useAuth();
  // Read once from the address bar (or from memory after the trip to /login); never stored elsewhere.
  const [token] = useState(() => captureInvitationToken(window.location.search));
  const [busy, setBusy] = useState(false);
  const [accepted, setAccepted] = useState<Accepted | null>(null);
  const [failure, setFailure] = useState<InvitationFailure | null>(null);
  const wait = useCountdown();
  const headingRef = useRef<HTMLHeadingElement>(null);

  useEffect(() => {
    // Strip the token from the address bar and this history entry before anything else can see it.
    if (new URLSearchParams(window.location.search).has('token')) {
      window.history.replaceState(window.history.state, '', window.location.pathname);
    }
  }, []);

  useEffect(() => {
    if (accepted || failure) headingRef.current?.focus();
  }, [accepted, failure]);

  async function handleAccept() {
    if (!token || busy || wait.active) return;
    // The previous message stays until the new outcome replaces it: clearing it now would shift the
    // button, and the second click of a double-click would land on whatever moves under the pointer.
    setBusy(true);
    try {
      // The accept response looks the same for new and existing members; the prior list tells them apart.
      const before = await listWorkspaces(request).catch(() => null);
      const workspace = await acceptInvitation(request, token);
      clearInvitationToken();
      setAccepted({ workspace, alreadyMember: before?.some((item) => item.id === workspace.id) ?? false });
    } catch (reason) {
      const next = invitationFailure(reason);
      if (isFinalFailure(next)) clearInvitationToken();
      if (next.kind === 'rateLimited') wait.start(next.retryAfterSeconds);
      setFailure(next);
    } finally {
      setBusy(false);
    }
  }

  if (!ready) {
    return <AuthLayout title="Lời mời tham gia workspace"><p className="text-sm text-slate-500 dark:text-slate-400" role="status">Đang kiểm tra phiên đăng nhập…</p></AuthLayout>;
  }

  if (accepted) {
    const { workspace, alreadyMember } = accepted;
    return (
      <AuthLayout
        headingRef={headingRef}
        title={alreadyMember ? 'Bạn đã là thành viên' : 'Đã tham gia workspace'}
        footer={<Link className={linkClass} to="/workspaces">Xem tất cả workspace</Link>}
      >
        <div className="space-y-5">
          <FormAlert tone="success">
            {alreadyMember
              ? <>Bạn đã ở trong workspace này từ trước; vai trò hiện tại được giữ nguyên.</>
              : <>Bạn đã tham gia workspace thành công.</>}
          </FormAlert>
          <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-2 rounded-2xl border border-slate-200 p-4 text-sm dark:border-slate-800">
            <dt className="text-slate-500 dark:text-slate-400">Workspace</dt>
            <dd className="min-w-0 break-words font-semibold text-slate-900 dark:text-white">{workspace.name}</dd>
            <dt className="text-slate-500 dark:text-slate-400">Vai trò</dt>
            <dd className="font-semibold text-slate-900 dark:text-white">{workspaceRoleLabel(workspace.role)}</dd>
          </dl>
          <Link className={primaryClass} to={`/boards?workspace=${encodeURIComponent(workspace.id)}`}>Vào workspace</Link>
        </div>
      </AuthLayout>
    );
  }

  if (!token) {
    return (
      <AuthLayout title="Không tìm thấy lời mời" footer={<Link className={linkClass} to={user ? '/workspaces' : '/login'}>{user ? 'Xem danh sách workspace' : 'Đăng nhập'}</Link>}>
        <FormAlert tone="error">Liên kết mời không đầy đủ hoặc đã được dùng trên trang này. Hãy mở lại liên kết trong email mời.</FormAlert>
      </AuthLayout>
    );
  }

  if (!user) {
    return (
      <AuthLayout
        title="Bạn được mời tham gia workspace"
        description="Đăng nhập bằng email đã nhận lời mời để chấp nhận."
      >
        <div className="space-y-4">
          <Link className={primaryClass} to={loginPathWithReturnTo(ACCEPT_PATH)}>Đăng nhập để chấp nhận</Link>
          <Link className={secondaryClass} to="/register">Tạo tài khoản mới</Link>
          <p className="text-xs leading-5 text-slate-500 dark:text-slate-400">
            Chưa có tài khoản? Hãy đăng ký bằng đúng email được mời. Sau khi xác minh email, mở lại liên kết mời để tham gia.
          </p>
        </div>
      </AuthLayout>
    );
  }

  const final = failure !== null && isFinalFailure(failure);
  return (
    <AuthLayout
      headingRef={headingRef}
      title={final ? 'Không thể chấp nhận lời mời' : 'Chấp nhận lời mời'}
      description={final ? undefined : <>Bạn đang đăng nhập bằng <span className="break-all font-medium text-slate-700 dark:text-slate-200">{user.email}</span>.</>}
      footer={<Link className={linkClass} to="/workspaces">Xem danh sách workspace</Link>}
    >
      <div className="space-y-5">
        {failure && (
          <FormAlert tone="error">
            {failureMessage(failure, user.email)}
            {(failure.kind === 'uncertain' || failure.kind === 'invalid') && (
              <> Nếu bạn vừa chấp nhận, hãy <Link className={linkClass} to="/workspaces">kiểm tra danh sách workspace</Link>.</>
            )}
          </FormAlert>
        )}

        {failure?.kind === 'emailMismatch' && (
          // Keeps the token in memory: after signing in with the invited account the user lands back here.
          <SecondaryButton className="w-full" onClick={signOut}>Đăng xuất để đổi tài khoản</SecondaryButton>
        )}

        {!final && failure?.kind !== 'emailMismatch' && (
          <button type="button" className={primaryClass} disabled={busy || wait.active} aria-busy={busy || undefined} onClick={() => void handleAccept()}>
            {busy ? 'Đang chấp nhận…' : wait.active ? `Thử lại sau ${wait.remaining} giây` : failure ? 'Thử lại' : 'Chấp nhận lời mời'}
          </button>
        )}
      </div>
    </AuthLayout>
  );
}
