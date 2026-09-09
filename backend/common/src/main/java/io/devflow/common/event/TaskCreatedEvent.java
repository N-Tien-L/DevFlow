package io.devflow.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code task.created} — published by Board when a card is created.
 * Consumed by AI service and Notification (ARCHITECTURE.md Section 4).
 *
 * @param taskId      id of the created task/card
 * @param projectId   project (workspace) the task belongs to
 * @param description task description — input for AI features such as task breakdown
 *                    (PRODUCT_SPEC.md Section 5.2b)
 */
public record TaskCreatedEvent(UUID taskId, UUID projectId, String description, Instant occurredAt)
        implements DevFlowEvent {

    public TaskCreatedEvent(UUID taskId, UUID projectId, String description) {
        this(taskId, projectId, description, Instant.now());
    }

    @Override
    public String eventType() {
        return EventTypes.TASK_CREATED;
    }
}
