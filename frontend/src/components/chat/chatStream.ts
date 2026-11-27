import type { ChatFrame, ChatFrameType, ProposedTask } from '../../types/chat';

/** The answer the panel is waiting for. Frames of any other conversation are ignored. */
export interface PendingStream {
  conversationId: string;
  messageId: string;
  question: string;
  nextSeq: number;
  buffered: Map<number, string>;
  /** Chunk count announced by END, once it arrived (END may overtake the last chunks). */
  endSeq: number | null;
}

export type StreamUpdate =
  | { kind: 'ignore' }
  | { kind: 'text'; text: string; done: boolean }
  | { kind: 'proposal'; content: string; proposals: ProposedTask[] }
  | { kind: 'cancelled' }
  | { kind: 'error'; code: string | undefined; message: string };

const FRAME_TYPES: ReadonlySet<string> = new Set<ChatFrameType>(['CHUNK', 'TASK_PROPOSAL', 'END', 'ERROR']);

export function createPendingStream(conversationId: string, messageId: string, question: string): PendingStream {
  return { conversationId, messageId, question, nextSeq: 0, buffered: new Map(), endSeq: null };
}

/** Parses a `/user/queue/chat` frame body; anything malformed yields null. */
export function parseChatFrame(body: string): ChatFrame | null {
  let value: unknown;
  try {
    value = JSON.parse(body);
  } catch {
    return null;
  }
  if (typeof value !== 'object' || value === null) return null;
  const frame = value as Record<string, unknown>;
  if (
    typeof frame.conversationId !== 'string' ||
    typeof frame.seq !== 'number' ||
    !Number.isInteger(frame.seq) ||
    typeof frame.type !== 'string' ||
    !FRAME_TYPES.has(frame.type)
  ) {
    return null;
  }
  const metadata = typeof frame.metadata === 'object' && frame.metadata !== null
    ? (frame.metadata as ChatFrame['metadata'])
    : null;
  return {
    conversationId: frame.conversationId,
    seq: frame.seq,
    type: frame.type as ChatFrameType,
    content: typeof frame.content === 'string' ? frame.content : '',
    metadata,
  };
}

function proposalsFrom(frame: ChatFrame): ProposedTask[] {
  const raw = frame.metadata?.proposals;
  if (!Array.isArray(raw)) return [];
  return raw.filter(
    (item): item is ProposedTask =>
      typeof item === 'object' && item !== null && typeof item.title === 'string' && item.title.trim().length > 0,
  ).map((item) => ({
    title: item.title,
    description: typeof item.description === 'string' ? item.description : '',
    checklist: Array.isArray(item.checklist) ? item.checklist.filter((step): step is string => typeof step === 'string') : [],
  }));
}

/**
 * Applies one frame to the pending stream, reordering chunks by `seq` (the server preserves order per
 * session, but a gap must never garble the answer). Mutates `stream`.
 */
export function applyFrame(stream: PendingStream | null, frame: ChatFrame): StreamUpdate {
  if (!stream || frame.conversationId !== stream.conversationId) return { kind: 'ignore' };

  switch (frame.type) {
    case 'CHUNK': {
      if (frame.seq < stream.nextSeq || stream.buffered.has(frame.seq)) return { kind: 'ignore' };
      stream.buffered.set(frame.seq, frame.content);
      let text = '';
      while (stream.buffered.has(stream.nextSeq)) {
        text += stream.buffered.get(stream.nextSeq);
        stream.buffered.delete(stream.nextSeq);
        stream.nextSeq += 1;
      }
      const done = stream.endSeq !== null && stream.nextSeq >= stream.endSeq;
      return text || done ? { kind: 'text', text, done } : { kind: 'ignore' };
    }
    case 'TASK_PROPOSAL':
      stream.nextSeq = Math.max(stream.nextSeq, frame.seq + 1);
      return { kind: 'proposal', content: frame.content, proposals: proposalsFrom(frame) };
    case 'END':
      if (frame.seq < 0) return { kind: 'cancelled' };
      stream.endSeq = frame.seq;
      return stream.nextSeq >= frame.seq ? { kind: 'text', text: '', done: true } : { kind: 'ignore' };
    case 'ERROR':
      return { kind: 'error', code: frame.metadata?.code, message: frame.content };
    default:
      return { kind: 'ignore' };
  }
}

const ERROR_MESSAGES: Record<string, string> = {
  WORKSPACE_ACCESS_DENIED: 'Bạn không có quyền truy cập workspace này.',
  WORKSPACE_REQUIRED: 'Hãy chọn một workspace trước khi hỏi.',
  AUTHENTICATION_REQUIRED: 'Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.',
  AI_RATE_LIMITED: 'Bạn đã gửi quá nhiều yêu cầu AI. Vui lòng đợi một lúc rồi thử lại.',
  AI_CHAT_STREAMS_LIMIT: 'Đang có quá nhiều câu trả lời chạy cùng lúc. Hãy đợi hoặc dừng bớt rồi thử lại.',
  AI_CHAT_BUSY: 'Trợ lý AI đang bận. Vui lòng thử lại sau giây lát.',
  AI_CHAT_FAILED: 'Đã có lỗi khi xử lý tin nhắn. Vui lòng thử lại.',
  AI_OUTPUT_INVALID: 'AI trả về câu trả lời không dùng được. Hãy thử lại hoặc diễn đạt khác.',
  MEMBERSHIP_CHECK_UNAVAILABLE: 'Tạm thời chưa xác minh được quyền truy cập. Vui lòng thử lại sau.',
  PROJECT_DATA_UNAVAILABLE: 'Tạm thời không đọc được dữ liệu dự án. Vui lòng thử lại sau.',
  AI_MESSAGE_REQUIRED: 'Hãy nhập nội dung tin nhắn.',
  AI_QUESTION_REQUIRED: 'Hãy nhập câu hỏi.',
  AI_DESCRIPTION_REQUIRED: 'Hãy mô tả tính năng cần chia task.',
  AI_MESSAGE_TOO_LONG: 'Tin nhắn tối đa 2000 ký tự.',
  AI_QUESTION_TOO_LONG: 'Câu hỏi về dự án tối đa 1000 ký tự.',
  AI_DESCRIPTION_TOO_LONG: 'Mô tả để chia task quá dài.',
};

const NOT_RETRYABLE = new Set([
  'WORKSPACE_ACCESS_DENIED',
  'WORKSPACE_REQUIRED',
  'AUTHENTICATION_REQUIRED',
  'AI_MESSAGE_REQUIRED',
  'AI_QUESTION_REQUIRED',
  'AI_DESCRIPTION_REQUIRED',
  'AI_MESSAGE_TOO_LONG',
  'AI_QUESTION_TOO_LONG',
  'AI_DESCRIPTION_TOO_LONG',
]);

export function chatErrorMessage(code: string | undefined, serverMessage: string) {
  return (code && ERROR_MESSAGES[code]) || serverMessage || 'Đã có lỗi khi xử lý tin nhắn. Vui lòng thử lại.';
}

export function isRetryable(code: string | undefined) {
  return !code || !NOT_RETRYABLE.has(code);
}
