package io.devflow.board.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateColumnRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull ColumnStatusCategory statusCategory) {
}
