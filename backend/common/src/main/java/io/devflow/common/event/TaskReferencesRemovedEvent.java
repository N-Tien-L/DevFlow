package io.devflow.common.event;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Board publishes bounded batches inside the deleting transaction; consumers must join it. */
public record TaskReferencesRemovedEvent(Set<UUID> taskIds, Instant occurredAt) implements DevFlowEvent {
    public TaskReferencesRemovedEvent(Set<UUID> taskIds) { this(Set.copyOf(taskIds), Instant.now()); }
    public TaskReferencesRemovedEvent {
        taskIds = Set.copyOf(taskIds);
        if (taskIds.isEmpty() || taskIds.size() > 100) throw new IllegalArgumentException("Invalid cleanup batch");
    }
    @Override public String eventType() { return EventTypes.TASK_REFERENCES_REMOVED; }
}
