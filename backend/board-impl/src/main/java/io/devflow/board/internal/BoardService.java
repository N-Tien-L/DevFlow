package io.devflow.board.internal;

import io.devflow.board.api.BoardApi;
import io.devflow.board.api.TaskSummary;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Board module core service. Owns: boards, lists, cards/tasks, assignees, due dates,
 * comments, activity history (ARCHITECTURE.md Section 3).
 *
 * <p>TODO(PRODUCT_SPEC Section 5.1): board/list/card CRUD with drag-and-drop ordering,
 * assignees, due dates, labels, search &amp; filter, comments &amp; activity history.
 *
 * <p>When implementing CRUD, publish events on the bus per ARCHITECTURE.md Section 4:
 * {@code TaskCreatedEvent} on card creation and {@code TaskStatusChangedEvent}
 * (cause MANUAL) on drag-and-drop — Notification and the AI service subscribe to these.
 */
@Service
public class BoardService implements BoardApi {

    private final ApplicationEventPublisher eventPublisher;

    public BoardService(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @Override
    public List<TaskSummary> findTasksByProject(UUID projectId) {
        // TODO(PRODUCT_SPEC 5.1): query tasks by project and map them to TaskSummary.
        throw new UnsupportedOperationException("Not implemented yet — scaffold stub");
    }

    @Override
    public Optional<TaskSummary> findTask(UUID taskId) {
        // TODO(PRODUCT_SPEC 5.1): look up the task entity and map it to a TaskSummary.
        throw new UnsupportedOperationException("Not implemented yet — scaffold stub");
    }

    // TODO(PRODUCT_SPEC 5.1): createCard(...) — persist the card, then
    //   eventPublisher.publishEvent(new TaskCreatedEvent(taskId, projectId, description));
    // TODO(PRODUCT_SPEC 5.1): moveCard(...) — persist the new column, then
    //   eventPublisher.publishEvent(new TaskStatusChangedEvent(taskId, oldStatus, newStatus, MANUAL));
}
