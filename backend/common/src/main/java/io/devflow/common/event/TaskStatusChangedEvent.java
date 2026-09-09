package io.devflow.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code task.status_changed} — published by Board (manual drag-and-drop) or Git &amp; CI
 * (automatic update from commit/PR activity, PRODUCT_SPEC.md Section 5.2a).
 * Consumed by Notification and AI service (ARCHITECTURE.md Section 4).
 *
 * @param taskId    id of the task whose status changed
 * @param oldStatus previous status column name (e.g. "To Do")
 * @param newStatus new status column name (e.g. "In Progress")
 * @param cause     what triggered the change — manual user action or Git/CI automation
 */
public record TaskStatusChangedEvent(
        UUID taskId, String oldStatus, String newStatus, StatusChangeCause cause, Instant occurredAt)
        implements DevFlowEvent {

    public TaskStatusChangedEvent(UUID taskId, String oldStatus, String newStatus, StatusChangeCause cause) {
        this(taskId, oldStatus, newStatus, cause, Instant.now());
    }

    @Override
    public String eventType() {
        return EventTypes.TASK_STATUS_CHANGED;
    }

    /** Why the status changed — {@code manual} (user dragged the card) or {@code git} (commit/PR activity). */
    public enum StatusChangeCause {
        MANUAL,
        GIT
    }
}
