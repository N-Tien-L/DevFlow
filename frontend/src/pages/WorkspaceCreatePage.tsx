import { Link } from 'react-router-dom';
import { AuthLayout } from '../components/auth/AuthLayout';
import { FormAlert, linkClass } from '../components/auth/FormControls';

// Placeholder route for the workspace-selection empty state. POST /api/v1/auth/workspaces is still a 501 stub;
// T-007 replaces this page with the real creation form.
export default function WorkspaceCreatePage() {
  return (
    <AuthLayout
      title="Tạo workspace"
      footer={<Link className={linkClass} to="/workspaces">← Quay lại danh sách workspace</Link>}
    >
      <FormAlert tone="info">
        Tính năng tạo workspace sắp ra mắt. Trong lúc chờ, hãy nhờ quản trị viên mời bạn vào một workspace có sẵn.
      </FormAlert>
    </AuthLayout>
  );
}
