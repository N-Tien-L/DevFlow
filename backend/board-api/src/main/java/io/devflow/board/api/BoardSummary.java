package io.devflow.board.api;

import java.time.Instant;
import java.util.UUID;

public record BoardSummary(
        UUID id,
        UUID workspaceId,
        String name,
        String description,
        boolean archived,
        Instant createdAt,
        Instant updatedAt) {
}
