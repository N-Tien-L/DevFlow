package io.devflow.board.api;

import java.time.Instant;
import java.util.UUID;

public record TaskCommentResponse(
        UUID id, UUID taskId, UUID authorId, String content, Instant createdAt, Instant updatedAt) {}
