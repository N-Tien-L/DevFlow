package io.devflow.ai.api;

import java.util.Map;
import java.util.UUID;

/**
 * Outbound STOMP message payload delivered to the client over {@code /user/queue/chat} (T-034).
 *
 * @param conversationId conversation UUID
 * @param seq            zero-indexed monotonic sequence number; END carries the chunk count, and -1 marks
 *                       an ERROR or the END that acknowledges a cancel
 * @param type           message type (CHUNK, TASK_PROPOSAL, END, ERROR)
 * @param content        chunk or error text
 * @param metadata       optional structured metadata ({@code proposals} for TASK_PROPOSAL, {@code code} for
 *                       ERROR)
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

    /**
     * Terminal error frame. {@code code} is the stable machine-readable reason (e.g. {@code AI_RATE_LIMITED},
     * {@code WORKSPACE_ACCESS_DENIED}) carried as {@code metadata.code}; {@code message} is safe to display.
     */
    public static ChatMessageResponse error(UUID conversationId, String code, String message) {
        return new ChatMessageResponse(conversationId, -1, ChatMessageType.ERROR, message, Map.of("code", code));
    }
}
