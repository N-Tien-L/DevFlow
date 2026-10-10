package io.devflow.board.api;

import java.util.List;
import java.util.UUID;

/** Small immutable mutation metadata. Task and column values remain authoritative in the REST API. */
public record BoardMutationData(
        UUID taskId,
        UUID columnId,
        UUID sourceColumnId,
        UUID destinationColumnId,
        Integer fromPosition,
        Integer toPosition,
        List<UUID> affectedColumnIds,
        List<String> changedFields,
        List<BoardRefreshTarget> refresh) {

    public BoardMutationData {
        affectedColumnIds = affectedColumnIds == null ? List.of() : List.copyOf(affectedColumnIds);
        changedFields = changedFields == null ? List.of() : List.copyOf(changedFields);
        refresh = refresh == null ? List.of() : List.copyOf(refresh);
    }
}
