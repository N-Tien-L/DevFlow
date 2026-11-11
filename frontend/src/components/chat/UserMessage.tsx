import type { ChatMessage } from '../../types/chat';

interface UserMessageProps {
  message: ChatMessage;
}

export function UserMessage({ message }: UserMessageProps) {
  return (
    <div className="flex justify-end gap-2 my-2">
      <div className="flex flex-col items-end max-w-[85%]">
        <div className="rounded-2xl rounded-tr-sm bg-blue-600 px-3.5 py-2 text-sm text-white shadow-sm dark:bg-blue-500">
          <p className="whitespace-pre-wrap leading-relaxed">{message.content}</p>
        </div>
        <span className="mt-1 text-3xs text-slate-400 dark:text-slate-500">
          {message.timestamp}
        </span>
      </div>
    </div>
  );
}
