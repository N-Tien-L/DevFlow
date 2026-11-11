import { useEffect, useRef } from 'react';
import { AssistantMessage } from './AssistantMessage';
import { UserMessage } from './UserMessage';
import type { ApiRequest } from '../../api/boardApi';
import type { ColumnResponse } from '../../types/board';
import type { ChatMessage } from '../../types/chat';

interface MessageListProps {
  messages: ChatMessage[];
  columns: ColumnResponse[];
  request: ApiRequest;
  onSelectPrompt?: (promptText: string) => void;
}

const SAMPLE_PROMPTS = [
  'Dự án còn bao nhiêu task chưa hoàn thành?',
  'Ai đang phụ trách các task trong sprint?',
  'Gợi ý chia task cho tính năng thanh toán VNPay',
  'Tóm tắt tiến độ công việc trên board',
];

export function MessageList({ messages, columns, request, onSelectPrompt }: MessageListProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const isNearBottomRef = useRef(true);

  const handleScroll = () => {
    const el = containerRef.current;
    if (!el) return;
    const distanceToBottom = el.scrollHeight - el.scrollTop - el.clientHeight;
    isNearBottomRef.current = distanceToBottom < 80;
  };

  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;
    if (isNearBottomRef.current) {
      el.scrollTop = el.scrollHeight;
    }
  }, [messages]);

  return (
    <div
      ref={containerRef}
      onScroll={handleScroll}
      className="flex-1 overflow-y-auto px-4 py-3 space-y-1 text-sm bg-slate-50/50 dark:bg-slate-950/40"
    >
      {messages.length === 0 ? (
        <div className="flex flex-col items-center justify-center h-full text-center px-4 py-8">
          <div className="h-12 w-12 rounded-2xl bg-indigo-100 text-indigo-600 flex items-center justify-center mb-3 dark:bg-indigo-950/60 dark:text-indigo-400">
            <svg className="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.75} d="M13 10V3L4 14h7v7l9-11h-7z" />
            </svg>
          </div>
          <h4 className="text-sm font-semibold text-slate-800 dark:text-slate-200">
            Trợ lý AI DevFlow
          </h4>
          <p className="mt-1 text-xs text-slate-500 dark:text-slate-400 max-w-xs leading-relaxed">
            Hỏi đáp trực tiếp về tiến độ dự án, tra cứu board hoặc nhận gợi ý chia nhỏ task từ mô tả.
          </p>

          <div className="mt-5 w-full space-y-1.5 text-left">
            <span className="text-2xs font-semibold uppercase tracking-wider text-slate-400 dark:text-slate-500">
              Gợi ý câu hỏi
            </span>
            {SAMPLE_PROMPTS.map((prompt, idx) => (
              <button
                key={idx}
                type="button"
                onClick={() => onSelectPrompt?.(prompt)}
                className="w-full text-left rounded-lg border border-slate-200 bg-white px-3 py-2 text-xs text-slate-700 shadow-xs hover:border-indigo-300 hover:bg-indigo-50/50 transition-colors dark:border-slate-800 dark:bg-slate-900 dark:text-slate-300 dark:hover:border-indigo-700 dark:hover:bg-indigo-950/30"
              >
                {prompt}
              </button>
            ))}
          </div>
        </div>
      ) : (
        messages.map((m) =>
          m.role === 'user' ? (
            <UserMessage key={m.id} message={m} />
          ) : (
            <AssistantMessage
              key={m.id}
              message={m}
              columns={columns}
              request={request}
            />
          )
        )
      )}
    </div>
  );
}
