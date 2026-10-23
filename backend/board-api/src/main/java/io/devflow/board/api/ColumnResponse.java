package io.devflow.board.api;

import java.time.Instant;
import java.util.UUID;

public record ColumnResponse(
        UUID id,
        UUID boardId,
        String name,
        int position,
        ColumnStatusCategory statusCategory,
        Instant createdAt,
        Instant updatedAt) {
}
