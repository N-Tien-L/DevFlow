import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { acceptInvitation, listWorkspaces } from '../api/boardApi';
import { useAuth } from '../auth/AuthProvider';
import { captureInvitationToken, clearInvitationToken, invitationFailure, isFinalFailure, type InvitationFailure } from '../auth/invitation';
import { loginPathWithReturnTo } from '../auth/returnTo';
import { removeTokenFromAddressBar } from '../auth/urlToken';
import { AuthLayout } from '../components/auth/AuthLayout';
import {
  FormAlert,
  SecondaryButton,
  linkClass,
  primaryLinkClass,
  secondaryLinkClass,
} from '../components/auth/FormControls';
import { ArrowRightIcon } from '../components/common/Icons';
import { Avatar, LoadingSpinner, RoleBadge } from '../components/common/UiPrimitives';
import { useCountdown } from '../hooks/useCountdown';
import type { WorkspaceSummary } from '../types/board';

const ACCEPT_PATH = '/invitations/accept';

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
    removeTokenFromAddressBar();
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
    return (
      <AuthLayout title="Lời mời tham gia workspace">
        <div className="flex items-center gap-3 rounded-xl border border-slate-200/80 bg-slate-50/75 px-4 py-4 text-sm text-slate-600 dark:border-slate-800 dark:bg-slate-950/60 dark:text-slate-300" role="status">
          <LoadingSpinner className="size-5 shrink-0 text-indigo-600" />
          <span>Đang kiểm tra phiên đăng nhập…</span>
        </div>
      </AuthLayout>
    );
  }

  if (accepted) {
    const { workspace, alreadyMember } = accepted;
    return (
      <AuthLayout
        headingRef={headingRef}
        title={alreadyMember ? 'Bạn đã là thành viên' : 'Đã tham gia workspace'}
        footer={<Link className={linkClass} to="/workspaces">Xem tất cả workspace</Link>}
      >
        <div className="space-y-4">
          <FormAlert tone="success">
            {alreadyMember
              ? <>Bạn đã ở trong workspace này từ trước; vai trò hiện tại được giữ nguyên.</>
              : <>Bạn đã tham gia workspace thành công.</>}
          </FormAlert>
          <dl className="grid grid-cols-[auto_minmax(0,1fr)] items-center gap-x-4 gap-y-3 rounded-2xl border border-slate-200/90 bg-slate-50/70 p-4 text-sm dark:border-slate-800 dark:bg-slate-950/60">
            <dt className="text-slate-500 dark:text-slate-400">Workspace</dt>
            <dd className="min-w-0 break-words font-semibold text-slate-950 dark:text-white">{workspace.name}</dd>
            <dt className="text-slate-500 dark:text-slate-400">Mã định danh</dt>
            <dd className="font-mono-code min-w-0 truncate text-xs text-slate-600 dark:text-slate-300">{workspace.slug}</dd>
            <dt className="text-slate-500 dark:text-slate-400">Vai trò</dt>
            <dd><RoleBadge role={workspace.role} /></dd>
          </dl>
          <Link className={primaryLinkClass} to={`/boards?workspace=${encodeURIComponent(workspace.id)}`}>
            <span>Mở workspace ngay</span>
            <ArrowRightIcon className="size-4" />
          </Link>
        </div>
      </AuthLayout>
    );
  }

  if (!token) {
    return (
      <AuthLayout
        title="Không tìm thấy lời mời"
        footer={<Link className={linkClass} to={user ? '/workspaces' : '/login'}>{user ? 'Xem danh sách workspace' : 'Quay lại đăng nhập'}</Link>}
      >
        <div className="space-y-4">
          <FormAlert tone="error">
            Liên kết mời không đầy đủ hoặc đã được dùng trên trang này. Hãy mở lại liên kết mới nhất trong email mời.
          </FormAlert>
          <Link className={secondaryLinkClass} to={user ? '/workspaces' : '/login'}>
            {user ? 'Đi tới danh sách workspace' : 'Đăng nhập DevFlow'}
          </Link>
        </div>
      </AuthLayout>
    );
  }

  if (!user) {
    return (
      <AuthLayout
        title="Bạn được mời tham gia workspace"
        description="Đăng nhập bằng email đã nhận lời mời để xác nhận tham gia cùng nhóm."
      >
        <div className="space-y-3">
          <Link className={primaryLinkClass} to={loginPathWithReturnTo(ACCEPT_PATH)}>
            <span>Đăng nhập để chấp nhận</span>
            <ArrowRightIcon className="size-4" />
          </Link>
          <Link className={secondaryLinkClass} to="/register">Tạo tài khoản mới</Link>
          <p className="rounded-xl bg-slate-50 px-3.5 py-2.5 text-xs leading-5 text-slate-600 ring-1 ring-inset ring-slate-200/70 dark:bg-slate-950/60 dark:text-slate-400 dark:ring-slate-800">
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
      description={final ? undefined : 'Xác nhận tham gia workspace bằng tài khoản đang đăng nhập dưới đây.'}
      footer={<Link className={linkClass} to="/workspaces">Xem danh sách workspace</Link>}
    >
      <div className="space-y-4">
        {!final && (
          <div className="flex min-w-0 items-center gap-3 rounded-2xl border border-slate-200/90 bg-slate-50/75 p-3.5 dark:border-slate-800 dark:bg-slate-950/60">
            <Avatar seed={user.id} name={user.fullName || user.email} avatarUrl={user.avatarUrl} size="md" />
            <div className="min-w-0 flex-1">
              <p className="truncate text-sm font-semibold text-slate-900 dark:text-white">{user.fullName || 'Tài khoản DevFlow'}</p>
              <p className="truncate text-xs text-slate-600 dark:text-slate-400">{user.email}</p>
            </div>
          </div>
        )}

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
          <button
            type="button"
            className={primaryLinkClass}
            disabled={busy || wait.active}
            aria-busy={busy || undefined}
            onClick={() => void handleAccept()}
          >
            {busy && <LoadingSpinner className="size-4 text-white" />}
            <span>{busy ? 'Đang chấp nhận…' : wait.active ? `Thử lại sau ${wait.remaining} giây` : failure ? 'Thử lại' : 'Chấp nhận lời mời'}</span>
          </button>
        )}
      </div>
    </AuthLayout>
  );
}
