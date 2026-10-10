package io.devflow.board.api;

import java.time.Instant;
import java.util.UUID;

/** Compact Kanban card representation for paged task lists. */
public record TaskListItem(
        UUID id,
        UUID columnId,
        String title,
        TaskPriority priority,
        UUID assigneeId,
        Instant dueDate,
        int position,
        ColumnStatusCategory statusCategory,
        Instant updatedAt) {
}
