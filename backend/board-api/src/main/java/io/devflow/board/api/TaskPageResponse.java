package io.devflow.board.api;

import java.util.List;

/** Paged, deterministic task list for a single authorized column. */
public record TaskPageResponse(
        List<TaskListItem> items,
        int page,
        int size,
        long totalElements,
        int totalPages) {
    public TaskPageResponse {
        items = List.copyOf(items);
    }
}
