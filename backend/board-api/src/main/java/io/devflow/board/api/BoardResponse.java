package io.devflow.board.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BoardResponse(
        UUID id,
        UUID workspaceId,
        String name,
        String description,
        boolean archived,
        Instant createdAt,
        Instant updatedAt,
        List<ColumnResponse> columns) {

    public BoardResponse {
        columns = List.copyOf(columns);
    }
}
