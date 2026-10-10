package io.devflow.board.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/** Full replacement for task content; column and position are changed only by the move API. */
public record UpdateTaskRequest(
        @NotBlank String title,
        String description,
        @NotNull TaskPriority priority,
        UUID assigneeId,
        Instant dueDate) {
}
