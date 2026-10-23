package io.devflow.board.api;

import java.util.UUID;

/** Task after move and the columns whose lists should be refreshed by clients. */
public record TaskMoveResponse(TaskResponse task, UUID sourceColumnId, UUID destinationColumnId) {
}
