package io.devflow.common.event;

import java.time.Instant;
import java.util.Objects;
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
 * @param boardId   owning board id; null only for the compatibility constructor
 * @param workspaceId workspace the task belongs to; null only for the compatibility constructor
 * @param sourceColumnId previous column id; null only for the compatibility constructor
 * @param destinationColumnId new column id; null only for the compatibility constructor
 * @param oldStatusCategory previous normalized status category; null only for compatibility calls
 * @param newStatusCategory new normalized status category; null only for compatibility calls
 * @param eventId stable id for this publication attempt; null only for the compatibility constructor
 * @param correlationId originating request id; null only for the compatibility constructor
 * @param actorId authenticated actor, or null for a future system-originated Git transition
 */
public record TaskStatusChangedEvent(
        UUID taskId,
        String oldStatus,
        String newStatus,
        StatusChangeCause cause,
        UUID boardId,
        UUID workspaceId,
        UUID sourceColumnId,
        UUID destinationColumnId,
        String oldStatusCategory,
        String newStatusCategory,
        UUID eventId,
        UUID correlationId,
        UUID actorId,
        Instant occurredAt)
        implements DevFlowEvent {

    /** Compatibility constructor for existing consumers that only need the original payload. */
    @Deprecated(forRemoval = false)
    public TaskStatusChangedEvent(UUID taskId, String oldStatus, String newStatus, StatusChangeCause cause) {
        this(taskId, oldStatus, newStatus, cause, null, null, null, null, null, null,
                null, null, null, Instant.now());
    }

    /** Compatibility constructor for existing consumers that only need the original payload. */
    @Deprecated(forRemoval = false)
    public TaskStatusChangedEvent(
            UUID taskId, String oldStatus, String newStatus, StatusChangeCause cause, Instant occurredAt) {
        this(taskId, oldStatus, newStatus, cause, null, null, null, null, null, null,
                null, null, null, occurredAt);
    }

    public TaskStatusChangedEvent {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(oldStatus, "oldStatus");
        Objects.requireNonNull(newStatus, "newStatus");
        Objects.requireNonNull(cause, "cause");
        Objects.requireNonNull(occurredAt, "occurredAt");
        boolean legacy = boardId == null && workspaceId == null && sourceColumnId == null
                && destinationColumnId == null && oldStatusCategory == null && newStatusCategory == null
                && eventId == null && correlationId == null && actorId == null;
        if (!legacy) {
            Objects.requireNonNull(boardId, "boardId");
            Objects.requireNonNull(workspaceId, "workspaceId");
            Objects.requireNonNull(sourceColumnId, "sourceColumnId");
            Objects.requireNonNull(destinationColumnId, "destinationColumnId");
            Objects.requireNonNull(oldStatusCategory, "oldStatusCategory");
            Objects.requireNonNull(newStatusCategory, "newStatusCategory");
            if (oldStatus.isBlank() || newStatus.isBlank()) {
                throw new IllegalArgumentException("Status column names must not be blank");
            }
            if (!isSupportedCategory(oldStatusCategory) || !isSupportedCategory(newStatusCategory)) {
                throw new IllegalArgumentException("Status categories must match the Board status catalog");
            }
            Objects.requireNonNull(eventId, "eventId");
            Objects.requireNonNull(correlationId, "correlationId");
            if (sourceColumnId.equals(destinationColumnId)) {
                throw new IllegalArgumentException("A status change must move between different columns");
            }
            if (cause == StatusChangeCause.MANUAL) {
                Objects.requireNonNull(actorId, "actorId");
            }
        }
    }

    private static boolean isSupportedCategory(String value) {
        return switch (value) {
            case "TODO", "IN_PROGRESS", "IN_REVIEW", "DONE" -> true;
            default -> false;
        };
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
