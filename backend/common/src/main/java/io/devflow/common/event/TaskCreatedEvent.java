package io.devflow.common.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code task.created} — published by Board when a card is created.
 * Consumed by AI service and Notification (ARCHITECTURE.md Section 4).
 *
 * @param taskId      id of the created task/card
 * @param projectId   legacy name for the workspace id the task belongs to
 * @param description task description — input for AI features such as task breakdown
 *                    (PRODUCT_SPEC.md Section 5.2b)
 * @param boardId     owning board id; null only for legacy constructor calls
 * @param columnId    initial column id; null only for legacy constructor calls
 * @param eventId     stable id for this publication attempt; null only for legacy calls
 * @param correlationId originating request id; null only for legacy calls
 * @param actorId     authenticated user who created the task; null only for legacy calls
 */
public record TaskCreatedEvent(
        UUID taskId,
        UUID projectId,
        String description,
        UUID boardId,
        UUID columnId,
        UUID eventId,
        UUID correlationId,
        UUID actorId,
        Instant occurredAt)
        implements DevFlowEvent {

    /** Compatibility constructor for existing consumers that only need the original payload. */
    @Deprecated(forRemoval = false)
    public TaskCreatedEvent(UUID taskId, UUID projectId, String description) {
        this(taskId, projectId, description, null, null, null, null, null, Instant.now());
    }

    /** Compatibility constructor for existing consumers that only need the original payload. */
    @Deprecated(forRemoval = false)
    public TaskCreatedEvent(UUID taskId, UUID projectId, String description, Instant occurredAt) {
        this(taskId, projectId, description, null, null, null, null, null, occurredAt);
    }

    public TaskCreatedEvent {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        boolean legacy = boardId == null && columnId == null && eventId == null
                && correlationId == null && actorId == null;
        if (!legacy) {
            Objects.requireNonNull(boardId, "boardId");
            Objects.requireNonNull(columnId, "columnId");
            Objects.requireNonNull(eventId, "eventId");
            Objects.requireNonNull(correlationId, "correlationId");
            Objects.requireNonNull(actorId, "actorId");
        }
    }

    @Override
    public String eventType() {
        return EventTypes.TASK_CREATED;
    }
}
