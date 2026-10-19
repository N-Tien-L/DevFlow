package io.devflow.board.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CreateBoardRequest(
        @NotNull UUID workspaceId,
        @NotBlank @Size(max = 255) String name,
        @Size(max = 10_000) String description) {
}
