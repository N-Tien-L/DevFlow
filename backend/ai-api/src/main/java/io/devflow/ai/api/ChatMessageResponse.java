package io.devflow.ai.api;

import java.util.Map;
import java.util.UUID;

/**
 * Outbound STOMP message payload delivered to the client over {@code /user/queue/chat} (T-034).
 *
 * @param conversationId conversation UUID
 * @param seq            zero-indexed monotonic sequence number (-1 for error)
 * @param type           message type (CHUNK, TASK_PROPOSAL, END, ERROR)
 * @param content        chunk or error text
 * @param metadata       optional structured metadata (e.g. proposed subtasks)
 */
public record ChatMessageResponse(
        UUID conversationId,
        int seq,
        ChatMessageType type,
        String content,
        Map<String, Object> metadata) {

    public static ChatMessageResponse chunk(UUID conversationId, int seq, String content) {
        return new ChatMessageResponse(conversationId, seq, ChatMessageType.CHUNK, content, null);
    }

    public static ChatMessageResponse proposal(
            UUID conversationId, int seq, String content, Map<String, Object> metadata) {
        return new ChatMessageResponse(conversationId, seq, ChatMessageType.TASK_PROPOSAL, content, metadata);
    }

    public static ChatMessageResponse end(UUID conversationId, int seq) {
        return new ChatMessageResponse(conversationId, seq, ChatMessageType.END, "", null);
    }

    public static ChatMessageResponse error(UUID conversationId, String message) {
        return new ChatMessageResponse(conversationId, -1, ChatMessageType.ERROR, message, null);
    }
}
