import { Routes, Route } from 'react-router-dom';
import BoardPage from './pages/BoardPage';
import ChatPanel from './components/ChatPanel';

export default function App() {
  return (
    <div className="min-h-screen bg-gray-50 flex">
      <div className="flex-1">
        <Routes>
          <Route path="*" element={<BoardPage />} />
        </Routes>
      </div>
      <ChatPanel />
    </div>
  );
}
