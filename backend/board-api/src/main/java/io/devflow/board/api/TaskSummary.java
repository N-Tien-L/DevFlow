package io.devflow.board.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Read-only view of a task/card exposed to other modules. Internal Board entities
 * (comments, activity history, list ordering, ...) never leave the module
 * (ARCHITECTURE.md Section 10).
 */
public record TaskSummary(
        UUID taskId, UUID projectId, String title, String status, UUID assigneeId, LocalDate dueDate) {
}
