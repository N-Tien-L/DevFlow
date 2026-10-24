import { useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { createWorkspace } from '../api/boardApi';
import { AuthErrorCode, fieldErrors, problemCode, retryAfterSeconds } from '../api/problemDetail';
import { useAuth } from '../auth/AuthProvider';
import { authErrorMessage } from '../auth/authErrorMessage';
import { WORKSPACE_NAME_MAX_LENGTH, validateWorkspaceName } from '../auth/validation';
import { AuthLayout } from '../components/auth/AuthLayout';
import { FormAlert, SubmitButton, TextField, linkClass } from '../components/auth/FormControls';
import { useCountdown } from '../hooks/useCountdown';
import { ApiError } from '../types/board';

interface Failure {
  message: string;
  /** The request may have reached the server; the user should check the list before retrying. */
  uncertain?: boolean;
}

export default function WorkspaceCreatePage() {
  const { request } = useAuth();
  const navigate = useNavigate();
  const [name, setName] = useState('');
  const [submitted, setSubmitted] = useState(false);
  const [serverError, setServerError] = useState<string>();
  const [failure, setFailure] = useState<Failure | null>(null);
  const [busy, setBusy] = useState(false);
  const wait = useCountdown();
  const inputRef = useRef<HTMLInputElement>(null);

  const fieldError = serverError ?? (submitted ? validateWorkspaceName(name) : undefined);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy || wait.active) return;
    setSubmitted(true);
    setFailure(null);
    setServerError(undefined);
    if (validateWorkspaceName(name)) {
      inputRef.current?.focus();
      return;
    }
    setBusy(true);
    try {
      const workspace = await createWorkspace(request, name);
      navigate(`/boards?workspace=${encodeURIComponent(workspace.id)}`, { replace: true });
    } catch (reason) {
      const code = problemCode(reason);
      if (fieldErrors(reason).name) {
        // Server messages are English; the only server-side name rule beyond ours is "visible characters".
        setServerError(validateWorkspaceName(name) ?? 'Tên workspace cần có ký tự hiển thị (không chỉ gồm thẻ HTML hay khoảng trắng).');
        inputRef.current?.focus();
      } else if (code === 'WORKSPACE_LIMIT_REACHED') {
        setFailure({ message: 'Bạn đã đạt giới hạn số workspace có thể tạo. Hãy dùng một workspace hiện có hoặc xóa bớt workspace không dùng.' });
      } else if (code === AuthErrorCode.RateLimitExceeded) {
        wait.start(retryAfterSeconds(reason) ?? 60);
        setFailure({ message: authErrorMessage(reason) });
      } else if (reason instanceof ApiError && reason.uncertain) {
        setFailure({ message: 'Chưa rõ workspace đã được tạo hay chưa do mất kết nối với máy chủ.', uncertain: true });
      } else {
        setFailure({ message: authErrorMessage(reason, 'Không tạo được workspace. Vui lòng thử lại.') });
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <AuthLayout
      title="Tạo workspace"
      description="Workspace là nơi nhóm của bạn quản lý board và công việc. Bạn sẽ là chủ sở hữu."
      footer={<Link className={linkClass} to="/workspaces">← Quay lại danh sách workspace</Link>}
    >
      <form className="space-y-5" onSubmit={handleSubmit} noValidate>
        <TextField
          label="Tên workspace"
          autoComplete="organization"
          maxLength={WORKSPACE_NAME_MAX_LENGTH}
          value={name}
          inputRef={inputRef}
          error={fieldError}
          hint={`${name.trim().length}/${WORKSPACE_NAME_MAX_LENGTH} ký tự`}
          onChange={(event) => {
            setName(event.target.value);
            setServerError(undefined);
          }}
          required
          autoFocus
        />

        {failure && (
          <FormAlert tone="error">
            {failure.message}
            {failure.uncertain && <> Hãy <Link className={linkClass} to="/workspaces">kiểm tra danh sách workspace</Link> trước khi thử lại.</>}
          </FormAlert>
        )}

        <SubmitButton busy={busy} busyLabel="Đang tạo workspace…" disabled={wait.active}>
          {wait.active ? `Thử lại sau ${wait.remaining} giây` : 'Tạo workspace'}
        </SubmitButton>
      </form>
    </AuthLayout>
  );
}
