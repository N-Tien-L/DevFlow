package io.devflow.board.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Public interface of the Board module (ARCHITECTURE.md Section 3: Board owns boards,
 * lists, cards/tasks, assignees, due dates, comments, activity history). This is the ONLY
 * way other modules may read Board state synchronously — e.g. the AI service needs it to
 * answer project Q&amp;A (PRODUCT_SPEC.md Section 5.2b). Mutations requested by other
 * modules go through the event bus (Section 4).
 *
 * <p>Implementations live in {@code board-impl} and are never imported directly.
 */
public interface BoardApi {

    /** All tasks of a project — input for conversational project Q&amp;A (PRODUCT_SPEC 5.2b). */
    List<TaskSummary> findTasksByProject(UUID projectId);

    /** Single task lookup, e.g. to attach a CI failure summary (PRODUCT_SPEC 5.2a). */
    Optional<TaskSummary> findTask(UUID taskId);
}
