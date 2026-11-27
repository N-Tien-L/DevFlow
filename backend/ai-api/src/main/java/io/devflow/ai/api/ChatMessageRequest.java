package io.devflow.ai.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Incoming STOMP message payload sent by the web client to {@code /app/chat} (T-034).
 *
 * @param conversationId client-generated conversation UUID to correlate streaming chunks
 * @param workspaceId    workspace (or project) context for grounded answers and membership check
 * @param projectId      alias for {@code workspaceId} (for backwards and cross-spec compatibility)
 * @param message        user question or command, at most 2000 characters
 */
public record ChatMessageRequest(
        @NotNull UUID conversationId,
        UUID workspaceId,
        UUID projectId,
        @NotBlank @Size(max = 2000) String message) {

    public static final int MAX_MESSAGE_LENGTH = 2000;

    public ChatMessageRequest(UUID conversationId, UUID workspaceId, String message) {
        this(conversationId, workspaceId, workspaceId, message);
    }

    /**
     * Resolves the effective workspace ID from either {@code workspaceId} or {@code projectId}.
     */
    public UUID targetWorkspaceId() {
        return workspaceId != null ? workspaceId : projectId;
    }
}
