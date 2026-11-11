/**
 * Frontend Chat & WebSocket Type Definitions (T-036)
 */

export type ChatMessageType = 'CHUNK' | 'TASK_PROPOSAL' | 'END' | 'ERROR';

export interface ChatMessageRequest {
  conversationId: string;
  workspaceId: string;
  projectId?: string;
  message: string;
}

export interface CancelChatRequest {
  conversationId: string;
}

export interface ProposedTask {
  title: string;
  description: string;
  checklist?: string[];
}

export interface TaskProposalMetadata {
  proposals?: ProposedTask[];
  [key: string]: unknown;
}

export interface ChatMessageResponse {
  conversationId: string;
  seq: number;
  type: ChatMessageType;
  content: string;
  metadata?: TaskProposalMetadata | null;
}

export interface ChatMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  timestamp: string;
  isStreaming?: boolean;
  isError?: boolean;
  proposals?: ProposedTask[];
}

export interface ChatConversation {
  id: string;
  workspaceId: string;
  title: string;
  messages: ChatMessage[];
  isStreaming: boolean;
  createdAt: string;
}

export type ChatConnectionStatus = 'idle' | 'connecting' | 'connected' | 'reconnecting' | 'offline' | 'error';
