package io.devflow.board.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ReorderColumnRequest(@NotNull @Min(0) Integer position) {
}
