import { MarkdownRenderer } from './MarkdownRenderer';
import { TaskProposalCard } from './TaskProposalCard';
import type { ApiRequest } from '../../api/boardApi';
import type { ColumnResponse } from '../../types/board';
import type { ChatMessage } from '../../types/chat';

interface AssistantMessageProps {
  message: ChatMessage;
  columns: ColumnResponse[];
  request: ApiRequest;
}

export function AssistantMessage({ message, columns, request }: AssistantMessageProps) {
  const isError = message.isError;

  return (
    <div className="flex items-start gap-2.5 my-3" aria-live="polite">
      {/* Bot Avatar */}
      <div className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-gradient-to-tr from-indigo-600 to-purple-600 text-white shadow-sm ring-1 ring-white/20">
        <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M9.663 17h4.673M12 3v1m6.364 1.636l-.707.707M21 12h-1M4 12H3m3.343-5.657l-.707-.707m2.828 9.9a5 5 0 117.072 0l-.548.547A3.374 3.374 0 0014 18.469V19a2 2 0 11-4 0v-.531c0-.895-.356-1.754-.988-2.386l-.548-.547z"
          />
        </svg>
      </div>

      <div className="flex flex-col items-start max-w-[88%]">
        <div
          className={`rounded-2xl rounded-tl-sm px-3.5 py-2.5 text-sm shadow-sm transition-colors ${
            isError
              ? 'border border-red-200 bg-red-50 text-red-800 dark:border-red-900/60 dark:bg-red-950/40 dark:text-red-200'
              : 'border border-slate-200/80 bg-white text-slate-800 dark:border-slate-800 dark:bg-slate-900 dark:text-slate-100'
          }`}
        >
          {isError ? (
            <div className="flex items-start gap-2">
              <svg className="mt-0.5 h-4 w-4 shrink-0 text-red-500" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 8v4m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
              </svg>
              <span>{message.content}</span>
            </div>
          ) : (
            <>
              {message.content ? (
                <MarkdownRenderer content={message.content} />
              ) : message.isStreaming ? (
                <div className="flex items-center gap-1.5 py-1 text-slate-400 dark:text-slate-500" aria-label="AI đang soạn câu trả lời…">
                  <span className="h-1.5 w-1.5 animate-bounce rounded-full bg-slate-400 [animation-delay:-0.3s]"></span>
                  <span className="h-1.5 w-1.5 animate-bounce rounded-full bg-slate-400 [animation-delay:-0.15s]"></span>
                  <span className="h-1.5 w-1.5 animate-bounce rounded-full bg-slate-400"></span>
                </div>
              ) : null}

              {message.isStreaming && message.content && (
                <span className="inline-block h-3.5 w-1.5 animate-pulse bg-indigo-500 ml-1 rounded-xs align-middle" />
              )}
            </>
          )}

          {message.proposals && message.proposals.length > 0 && (
            <TaskProposalCard
              proposals={message.proposals}
              columns={columns}
              request={request}
            />
          )}
        </div>

        <span className="mt-1 text-3xs text-slate-400 dark:text-slate-500">
          {message.timestamp}
        </span>
      </div>
    </div>
  );
}
