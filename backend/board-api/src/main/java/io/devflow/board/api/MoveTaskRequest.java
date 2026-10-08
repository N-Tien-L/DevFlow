package io.devflow.board.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Target column and final zero-based position within that column. */
public record MoveTaskRequest(@NotNull UUID columnId, @NotNull @Min(0) Integer position) {
}
