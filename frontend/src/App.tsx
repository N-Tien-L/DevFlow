import { useEffect } from 'react';
import { Navigate, Route, Routes, useNavigate } from 'react-router-dom';
import { AuthProvider, useAuth } from './auth/AuthProvider';
import ChatPanel from './components/ChatPanel';
import BoardPage from './pages/BoardPage';
import LoginPage from './pages/LoginPage';

function ProtectedBoard() {
  const { user, ready } = useAuth();
  if (!ready) return <CenteredMessage message="Đang kiểm tra phiên đăng nhập…" />;
  if (!user) return <Navigate to="/login" replace />;
  return <BoardPage />;
}

function CenteredMessage({ message }: { message: string }) {
  return <div className="grid min-h-screen place-items-center text-sm text-slate-500" role="status">{message}</div>;
}

function AuthenticatedApp() {
  const { user, ready } = useAuth();
  const navigate = useNavigate();

  useEffect(() => {
    if (ready && user && window.location.pathname === '/login') navigate('/boards', { replace: true });
  }, [navigate, ready, user]);

  return (
    <div className="min-h-screen bg-slate-50">
      <Routes>
        <Route path="/login" element={ready && !user ? <LoginPage /> : <CenteredMessage message="Đang mở DevFlow…" />} />
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
