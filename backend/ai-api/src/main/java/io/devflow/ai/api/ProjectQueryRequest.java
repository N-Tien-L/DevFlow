package io.devflow.ai.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Request body of {@code POST /api/v1/ai/project-query}. A project is a workspace's board data, so the
 * caller must be a member of {@code projectId}; the question is treated strictly as data.
 */
public record ProjectQueryRequest(
        @NotNull(message = "projectId is required")
        UUID projectId,

        @NotBlank(message = "Question is required")
        @Size(max = ProjectQueryRequest.MAX_QUESTION_LENGTH,
                message = "Question must not exceed " + ProjectQueryRequest.MAX_QUESTION_LENGTH + " characters")
        String question
) {

    public static final int MAX_QUESTION_LENGTH = 1000;
}
