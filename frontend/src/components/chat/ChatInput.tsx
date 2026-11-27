import { forwardRef, useEffect, useRef, useState, type KeyboardEvent } from 'react';
import clsx from 'clsx';
import { CHAT_MAX_MESSAGE_LENGTH } from '../../types/chat';
import { SendIcon, XIcon } from '../common/Icons';

interface ChatInputProps {
  onSend: (message: string) => void;
  onCancel: () => void;
  streaming: boolean;
  /** Sending is not possible right now (still connecting, no workspace); typing stays possible. */
  disabled: boolean;
  disabledReason?: string;
}

export const ChatInput = forwardRef<HTMLTextAreaElement, ChatInputProps>(function ChatInput(
  { onSend, onCancel, streaming, disabled, disabledReason },
  ref,
) {
  const [text, setText] = useState('');
  const localRef = useRef<HTMLTextAreaElement | null>(null);
  const trimmed = text.trim();
  const overLimit = text.length > CHAT_MAX_MESSAGE_LENGTH;
  const canSubmit = Boolean(trimmed) && !overLimit && !streaming && !disabled;

  useEffect(() => {
    const element = localRef.current;
    if (!element) return;
    element.style.height = 'auto';
    element.style.height = `${Math.min(element.scrollHeight, 128)}px`;
  }, [text]);

  const submit = () => {
    if (!canSubmit) return;
    onSend(trimmed);
    setText('');
  };

  const handleKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
      event.preventDefault();
      submit();
    }
  };

  return (
    <div className="border-t border-slate-200/80 bg-white p-3">
      <label className="sr-only" htmlFor="devflow-chat-input">
        Nhập câu hỏi cho trợ lý AI
      </label>
      <div className="flex items-end gap-2">
        <textarea
          id="devflow-chat-input"
          ref={(element) => {
            localRef.current = element;
            if (typeof ref === 'function') ref(element);
            else if (ref) ref.current = element;
          }}
          value={text}
          rows={1}
          onChange={(event) => setText(event.target.value)}
          onKeyDown={handleKeyDown}
          aria-describedby="devflow-chat-input-hint"
          aria-invalid={overLimit || undefined}
          placeholder={streaming ? 'Trợ lý đang trả lời…' : 'Hỏi về dự án hoặc nhờ chia task…'}
          className="min-h-10 min-w-0 flex-1 resize-none rounded-xl border border-slate-300 bg-white px-3 py-2 text-sm text-slate-900 placeholder:text-slate-400 focus:border-indigo-500 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500/30"
        />
        {streaming ? (
          <button
            type="button"
            onClick={onCancel}
            aria-label="Dừng câu trả lời"
            className="grid size-10 shrink-0 place-items-center rounded-xl border border-rose-200 bg-rose-50 text-rose-700 transition hover:bg-rose-100 focus:outline-none focus-visible:ring-2 focus-visible:ring-rose-500"
          >
            <XIcon className="size-4" />
          </button>
        ) : (
          <button
            type="button"
            onClick={submit}
            disabled={!canSubmit}
            aria-label="Gửi tin nhắn"
            className="grid size-10 shrink-0 place-items-center rounded-xl bg-indigo-600 text-white transition hover:bg-indigo-700 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 disabled:cursor-not-allowed disabled:bg-slate-200 disabled:text-slate-400"
          >
            <SendIcon className="size-4" />
          </button>
        )}
      </div>
      <p id="devflow-chat-input-hint" className="mt-1.5 flex justify-between gap-2 text-[11px] text-slate-500">
        <span className="truncate">{disabled && disabledReason ? disabledReason : 'Enter để gửi, Shift+Enter xuống dòng'}</span>
        <span className={clsx('shrink-0 tabular-nums', overLimit && 'font-semibold text-rose-600')}>
          {text.length}/{CHAT_MAX_MESSAGE_LENGTH}
        </span>
      </p>
    </div>
  );
});
