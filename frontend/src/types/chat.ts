/**
 * Chat over STOMP (T-034 backend, T-036 panel).
 *
 * The client sends to `/app/chat` and `/app/chat/cancel` and receives only its own frames on
 * `/user/queue/chat`. The server keeps no conversation history, so the panel uses a fresh
 * `conversationId` for every question: it names one answer stream, and frames of any other
 * stream (another tab of the same user, a cancelled answer) are dropped.
 */

export type ChatFrameType = 'CHUNK' | 'TASK_PROPOSAL' | 'END' | 'ERROR';

export interface ChatMessageRequest {
  conversationId: string;
  workspaceId: string;
  message: string;
}

export interface ProposedTask {
  title: string;
  description: string;
  checklist?: string[] | null;
}

/** One frame from `/user/queue/chat`. END carries the chunk count as `seq`; -1 marks a cancel ack or an ERROR. */
export interface ChatFrame {
  conversationId: string;
  seq: number;
  type: ChatFrameType;
  content: string;
  metadata?: { proposals?: ProposedTask[]; code?: string } | null;
}

export type ChatMessageStatus = 'streaming' | 'done' | 'cancelled' | 'error';

export interface ChatMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  timestamp: string;
  status?: ChatMessageStatus;
  proposals?: ProposedTask[];
  /** Question to resend when the answer failed for a retryable reason. */
  retryText?: string;
}

export type ChatConnectionStatus = 'idle' | 'connecting' | 'connected' | 'reconnecting' | 'offline' | 'error';

export const CHAT_MAX_MESSAGE_LENGTH = 2000;
