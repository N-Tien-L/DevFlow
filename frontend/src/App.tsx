import { Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { AuthProvider, useAuth } from './auth/AuthProvider';
import { loginPathWithReturnTo, returnToFromSearch } from './auth/returnTo';
import ChatPanel from './components/ChatPanel';
import BoardPage from './pages/BoardPage';
import LoginPage from './pages/LoginPage';

function ProtectedBoard() {
  const { user, ready, sessionEnd } = useAuth();
  const location = useLocation();
  if (!ready) return <CenteredMessage message="Đang kiểm tra phiên đăng nhập…" />;
  if (!user) {
    // An explicit sign-out starts fresh; an expired session or a deep link comes back to where the user was.
    const returnTo = sessionEnd === 'signedOut' ? null : `${location.pathname}${location.search}${location.hash}`;
    return <Navigate to={loginPathWithReturnTo(returnTo)} replace />;
  }
  return <BoardPage />;
}

function LoginRoute() {
  const { user, ready } = useAuth();
  const location = useLocation();
  if (!ready) return <CenteredMessage message="Đang mở DevFlow…" />;
  if (user) return <Navigate to={returnToFromSearch(location.search)} replace />;
  return <LoginPage />;
}

function CenteredMessage({ message }: { message: string }) {
  return <div className="grid min-h-screen place-items-center text-sm text-slate-500" role="status">{message}</div>;
}

function AuthenticatedApp() {
  const { user } = useAuth();

  return (
    <div className="min-h-screen bg-slate-50">
      <Routes>
        <Route path="/login" element={<LoginRoute />} />
        <Route path="/boards" element={<ProtectedBoard />} />
        <Route path="/boards/:boardId" element={<ProtectedBoard />} />
        <Route path="*" element={<Navigate to="/boards" replace />} />
      </Routes>
      {user && <ChatPanel />}
    </div>
  );
}

export default function App() {
  return <AuthProvider><AuthenticatedApp /></AuthProvider>;
}
