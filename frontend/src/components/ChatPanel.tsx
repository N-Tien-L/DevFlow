import { useState } from 'react';

export default function ChatPanel() {
  const [isOpen, setIsOpen] = useState(false);
  const [messages] = useState([
    { id: '1', sender: 'ai' as const, text: 'Hello! I can help you with your project. (PRODUCT_SPEC 5.2b)' },
  ]);

  return (
    <>
      <button
        onClick={() => setIsOpen(!isOpen)}
        className="fixed bottom-6 right-6 bg-blue-600 text-white rounded-full w-14 h-14 shadow-lg hover:bg-blue-700 flex items-center justify-center text-xl z-50"
      >
        {isOpen ? '×' : '💬'}
      </button>

      {isOpen && (
        <div className="fixed bottom-24 right-6 w-80 bg-white rounded-lg shadow-xl border border-gray-200 flex flex-col z-50" style={{ height: '400px' }}>
          <div className="p-3 border-b bg-gray-50 rounded-t-lg">
            <h3 className="font-semibold text-sm">DevFlow Chat</h3>
          </div>
          <div className="flex-1 overflow-y-auto p-3 space-y-2">
            {messages.map((m) => (
              <div key={m.id} className={`text-sm p-2 rounded ${m.sender === 'ai' ? 'bg-gray-100' : 'bg-blue-100 ml-6'}`}>
                {m.text}
              </div>
            ))}
          </div>
          <div className="p-3 border-t">
            <input
              type="text"
              placeholder="Ask about your project..."
              className="w-full text-sm border rounded px-3 py-2 focus:outline-none focus:ring-1 focus:ring-blue-500"
              disabled
            />
            <p className="text-xs text-gray-400 mt-1">TODO: connect to AI service (PRODUCT_SPEC 5.2b)</p>
          </div>
        </div>
      )}
    </>
  );
}
