package io.devflow.board.api;

import java.util.List;

public record BoardPageResponse(
        List<BoardSummary> items,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public BoardPageResponse {
        items = List.copyOf(items);
    }
}
