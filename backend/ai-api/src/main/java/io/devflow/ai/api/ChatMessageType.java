package io.devflow.ai.api;

/**
 * Message types emitted to the caller on {@code /user/queue/chat} during streaming chat (T-034).
 */
public enum ChatMessageType {
    /** An incremental text chunk of the streaming answer. */
    CHUNK,
    /** An AI proposal of 3-5 subtasks (from TaskBreakdownService) ready for 1-click creation. */
    TASK_PROPOSAL,
    /** Terminal event indicating the stream finished successfully. */
    END,
    /** Terminal event indicating an error occurred (e.g. rate limit, access denied). */
    ERROR
}
