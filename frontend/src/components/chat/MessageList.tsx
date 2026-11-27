import { useEffect, useRef } from 'react';
import clsx from 'clsx';
import type { ApiRequest } from '../../api/boardApi';
import type { ColumnResponse } from '../../types/board';
import type { ChatMessage } from '../../types/chat';
import { AlertCircleIcon, RefreshIcon, SparklesIcon } from '../common/Icons';
import { MarkdownRenderer } from './MarkdownRenderer';
import { TaskProposalCard } from './TaskProposalCard';

interface MessageListProps {
  messages: ChatMessage[];
  columns: ColumnResponse[];
  columnsLoading: boolean;
  boardName: string | null;
  request: ApiRequest;
  canSend: boolean;
  onPrompt: (text: string) => void;
  onRetry: (text: string) => void;
}

const SAMPLE_PROMPTS = [
  'Dự án còn bao nhiêu task chưa hoàn thành?',
  'Những task nào đang quá hạn?',
  'Gợi ý chia task cho tính năng thanh toán VNPay',
];

function AssistantMessage({
  message,
  columns,
  columnsLoading,
  boardName,
  request,
  canRetry,
  onRetry,
}: {
  message: ChatMessage;
  columns: ColumnResponse[];
  columnsLoading: boolean;
  boardName: string | null;
  request: ApiRequest;
  canRetry: boolean;
  onRetry: (text: string) => void;
}) {
  const streaming = message.status === 'streaming';
  const failed = message.status === 'error';
  return (
    <li className="flex items-start gap-2" aria-busy={streaming}>
      <span className="mt-0.5 grid size-7 shrink-0 place-items-center rounded-lg bg-indigo-600 text-white" aria-hidden="true">
        <SparklesIcon className="size-3.5" />
      </span>
      <div className="min-w-0 max-w-[88%]">
        <p className="sr-only">Trợ lý AI:</p>
        <div
          className={clsx(
            'rounded-2xl rounded-tl-md border px-3.5 py-2.5 shadow-2xs',
            failed ? 'border-rose-200 bg-rose-50 text-rose-900' : 'border-slate-200/90 bg-white',
          )}
        >
          {failed ? (
            <div className="flex items-start gap-2 text-sm leading-6">
              <AlertCircleIcon className="mt-1 size-4 shrink-0 text-rose-600" />
              <span>{message.content}</span>
            </div>
          ) : message.content ? (
            <MarkdownRenderer content={message.content} />
          ) : streaming ? (
            <span className="flex items-center gap-1 py-2" aria-label="Trợ lý đang soạn câu trả lời">
              <span className="size-1.5 animate-bounce rounded-full bg-slate-400 [animation-delay:-0.3s]" />
              <span className="size-1.5 animate-bounce rounded-full bg-slate-400 [animation-delay:-0.15s]" />
              <span className="size-1.5 animate-bounce rounded-full bg-slate-400" />
            </span>
          ) : null}

          {message.status === 'cancelled' && (
            <p className="mt-1 text-[11px] font-medium text-slate-500">Đã dừng câu trả lời.</p>
          )}

          {message.proposals && message.proposals.length > 0 && (
            <TaskProposalCard
              proposals={message.proposals}
              columns={columns}
              columnsLoading={columnsLoading}
              boardName={boardName}
              request={request}
            />
          )}

          {failed && message.retryText && (
            <button
              type="button"
              onClick={() => onRetry(message.retryText ?? '')}
              disabled={!canRetry}
              className="mt-2 inline-flex min-h-8 items-center gap-1.5 rounded-lg border border-rose-200 bg-white px-2.5 text-xs font-semibold text-rose-700 transition hover:bg-rose-100 focus:outline-none focus-visible:ring-2 focus-visible:ring-rose-500 disabled:cursor-not-allowed disabled:opacity-50"
            >
              <RefreshIcon className="size-3.5" />
              <span>Thử lại</span>
            </button>
          )}
        </div>
        <span className="mt-1 block text-[11px] text-slate-400">{message.timestamp}</span>
      </div>
    </li>
  );
}

function UserMessage({ message }: { message: ChatMessage }) {
  return (
    <li className="flex justify-end">
      <div className="flex max-w-[85%] flex-col items-end">
        <p className="sr-only">Bạn:</p>
        <div className="rounded-2xl rounded-tr-md bg-slate-900 px-3.5 py-2 text-sm leading-6 text-white shadow-2xs">
          <p className="whitespace-pre-wrap break-words">{message.content}</p>
        </div>
        <span className="mt-1 text-[11px] text-slate-400">{message.timestamp}</span>
      </div>
    </li>
  );
}

export function MessageList({
  messages,
  columns,
  columnsLoading,
  boardName,
  request,
  canSend,
  onPrompt,
  onRetry,
}: MessageListProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const stickToBottomRef = useRef(true);

  useEffect(() => {
    const element = containerRef.current;
    if (element && stickToBottomRef.current) element.scrollTop = element.scrollHeight;
  }, [messages]);

  return (
    <div
      ref={containerRef}
      onScroll={() => {
        const element = containerRef.current;
        if (element) stickToBottomRef.current = element.scrollHeight - element.scrollTop - element.clientHeight < 80;
      }}
      className="devflow-scroll min-h-0 flex-1 overflow-y-auto bg-slate-50/60 px-4 py-4"
    >
      {messages.length === 0 ? (
        <div className="flex flex-col gap-3">
          <div className="rounded-xl border border-slate-200/90 bg-white p-3.5">
            <p className="text-sm font-semibold text-slate-900">Hỏi gì về dự án cũng được</p>
            <p className="mt-1 text-xs leading-5 text-slate-600">
              Trợ lý trả lời dựa trên dữ liệu board của workspace, và có thể gợi ý chia một tính năng thành các task để
              bạn tạo ngay.
            </p>
          </div>
          <p className="text-[11px] font-bold uppercase tracking-wider text-slate-500">Gợi ý câu hỏi</p>
          <div className="grid gap-2">
            {SAMPLE_PROMPTS.map((prompt) => (
              <button
                key={prompt}
                type="button"
                onClick={() => onPrompt(prompt)}
                disabled={!canSend}
                className="min-h-10 rounded-xl border border-slate-200/90 bg-white px-3 py-2 text-left text-xs font-semibold text-slate-800 transition hover:border-indigo-300 hover:bg-indigo-50/50 hover:text-indigo-700 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 disabled:cursor-not-allowed disabled:opacity-60"
              >
                {prompt}
              </button>
            ))}
          </div>
        </div>
      ) : (
        <ol role="log" aria-live="polite" aria-relevant="additions text" aria-label="Hội thoại với trợ lý AI" className="space-y-3.5">
          {messages.map((message) =>
            message.role === 'user' ? (
              <UserMessage key={message.id} message={message} />
            ) : (
              <AssistantMessage
                key={message.id}
                message={message}
                columns={columns}
                columnsLoading={columnsLoading}
                boardName={boardName}
                request={request}
                canRetry={canSend}
                onRetry={onRetry}
              />
            ),
          )}
        </ol>
      )}
    </div>
  );
}
