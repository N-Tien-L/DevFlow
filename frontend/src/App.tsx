import type { ReactNode } from 'react';
import { Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { AuthProvider, useAuth } from './auth/AuthProvider';
import { DEFAULT_AFTER_LOGIN, loginPathWithReturnTo, returnToFromSearch } from './auth/returnTo';
import ChatPanel from './components/ChatPanel';
import BoardPage from './pages/BoardPage';
import EmailVerificationPage from './pages/EmailVerificationPage';
import LoginPage from './pages/LoginPage';
import RegisterPage from './pages/RegisterPage';
import WorkspaceCreatePage from './pages/WorkspaceCreatePage';
import WorkspaceSelectionPage from './pages/WorkspaceSelectionPage';

function RequireAuth({ children }: { children: ReactNode }) {
  const { user, ready, sessionEnd } = useAuth();
  const location = useLocation();
  if (!ready) return <CenteredMessage message="Đang kiểm tra phiên đăng nhập…" />;
  if (!user) {
    // An explicit sign-out starts fresh; an expired session or a deep link comes back to where the user was.
    const returnTo = sessionEnd === 'signedOut' ? null : `${location.pathname}${location.search}${location.hash}`;
    return <Navigate to={loginPathWithReturnTo(returnTo)} replace />;
  }
  return children;
}

/** Login and register are for signed-out visitors; a signed-in user goes on to the app. */
function GuestOnly({ children }: { children: ReactNode }) {
  const { user, ready } = useAuth();
  const location = useLocation();
  if (!ready) return <CenteredMessage message="Đang mở DevFlow…" />;
  if (user) return <Navigate to={location.pathname === '/login' ? returnToFromSearch(location.search) : DEFAULT_AFTER_LOGIN} replace />;
  return children;
}

function CenteredMessage({ message }: { message: string }) {
  return <div className="grid min-h-screen place-items-center text-sm text-slate-500 dark:bg-slate-950 dark:text-slate-400" role="status">{message}</div>;
}

function AuthenticatedApp() {
  const { user } = useAuth();

  return (
    <div className="min-h-screen bg-slate-50 dark:bg-slate-950">
      <Routes>
        <Route path="/login" element={<GuestOnly><LoginPage /></GuestOnly>} />
        <Route path="/register" element={<GuestOnly><RegisterPage /></GuestOnly>} />
        <Route path="/verify-email" element={<EmailVerificationPage />} />
        <Route path="/workspaces" element={<RequireAuth><WorkspaceSelectionPage /></RequireAuth>} />
        <Route path="/workspaces/new" element={<RequireAuth><WorkspaceCreatePage /></RequireAuth>} />
        <Route path="/boards" element={<RequireAuth><BoardPage /></RequireAuth>} />
        <Route path="/boards/:boardId" element={<RequireAuth><BoardPage /></RequireAuth>} />
        <Route path="*" element={<Navigate to={DEFAULT_AFTER_LOGIN} replace />} />
      </Routes>
      {user && <ChatPanel />}
    </div>
  );
}

export default function App() {
  return <AuthProvider><AuthenticatedApp /></AuthProvider>;
}
