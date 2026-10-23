package io.devflow.board.api;

import jakarta.validation.constraints.NotBlank;

/** Plain-text comment submitted by the authenticated task viewer. */
public record CreateTaskCommentRequest(
        @NotBlank String content) {}
