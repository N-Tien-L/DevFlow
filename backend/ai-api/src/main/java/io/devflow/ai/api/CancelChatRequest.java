package io.devflow.ai.api;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Incoming STOMP message payload sent by the web client to {@code /app/chat/cancel} to abort an active stream.
 *
 * @param conversationId conversation UUID to abort
 */
public record CancelChatRequest(@NotNull UUID conversationId) {
}
