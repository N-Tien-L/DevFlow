package io.devflow.ai.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Request body of {@code POST /api/v1/ai/task-breakdown}. The caller must be a member of
 * {@code workspaceId}; the description is treated strictly as data, never as instructions.
 */
public record TaskBreakdownRequest(
        @NotNull(message = "workspaceId is required")
        UUID workspaceId,

        @NotBlank(message = "Description is required")
        @Size(max = TaskBreakdownRequest.MAX_DESCRIPTION_LENGTH,
                message = "Description must not exceed " + TaskBreakdownRequest.MAX_DESCRIPTION_LENGTH + " characters")
        String description
) {

    public static final int MAX_DESCRIPTION_LENGTH = 4000;
}
