package io.devflow.board.api;

import java.time.Instant;
import java.util.UUID;

/** Stable REST representation of one task without its comments or tag graph. */
public record TaskResponse(
        UUID id,
        UUID columnId,
        UUID boardId,
        UUID workspaceId,
        String title,
        String description,
        TaskPriority priority,
        UUID assigneeId,
        Instant dueDate,
        int position,
        ColumnStatusCategory statusCategory,
        Instant createdAt,
        Instant updatedAt) {
}
