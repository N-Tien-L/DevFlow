package io.devflow.common.event;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Best-effort after-commit notification emitted by Git &amp; CI whenever commits, pull requests,
 * or CI runs linked to one or more tasks change. Board consumes it to broadcast a board-scoped
 * STOMP invalidation ({@code CARD_UPDATED} with {@code changedFields=["gitActivity"]}).
 */
public record TaskGitActivityChangedEvent(
        UUID eventId,
        UUID workspaceId,
        UUID repositoryId,
        Set<UUID> taskIds,
        ActivityKind kind,
        Instant occurredAt) implements DevFlowEvent {

    public enum ActivityKind {
        COMMIT_LINKED,
        PR_UPDATED,
        CI_UPDATED
    }

    public TaskGitActivityChangedEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        Objects.requireNonNull(repositoryId, "repositoryId must not be null");
        Objects.requireNonNull(taskIds, "taskIds must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        taskIds = Set.copyOf(taskIds);
        if (taskIds.isEmpty() || taskIds.size() > 100) {
            throw new IllegalArgumentException("taskIds must contain between 1 and 100 task IDs");
        }
    }

    @Override
    public String eventType() {
        return EventTypes.TASK_GIT_ACTIVITY_CHANGED;
    }
}
