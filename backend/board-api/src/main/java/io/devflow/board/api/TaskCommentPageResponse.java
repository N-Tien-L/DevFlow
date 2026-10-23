package io.devflow.board.api;

import java.util.List;

public record TaskCommentPageResponse(
        List<TaskCommentResponse> items, int page, int size, long totalElements, int totalPages) {
    public TaskCommentPageResponse {
        items = List.copyOf(items);
    }
}
