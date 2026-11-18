package io.devflow.common.event;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Synchronous Board-owned lookup. The owning listener holds eligibility locks until commit. */
public final class TaskReferencesResolveRequestedEvent implements DevFlowEvent {
    public enum Outcome { ELIGIBLE, NOT_FOUND, INACTIVE }
    public record Resolution(UUID workspaceId, String key, UUID taskId, Outcome outcome) {
        public Resolution {
            Objects.requireNonNull(workspaceId);
            Objects.requireNonNull(key);
            Objects.requireNonNull(outcome);
            if ((outcome == Outcome.ELIGIBLE) != (taskId != null)) throw new IllegalArgumentException("Invalid resolution");
        }
    }
    private final UUID workspaceId;
    private final UUID correlationId;
    private final Set<String> keys;
    private final Instant occurredAt = Instant.now();
    private final Thread owner = Thread.currentThread();
    private int responses;
    private Map<String, Resolution> resolutions;

    public TaskReferencesResolveRequestedEvent(UUID workspaceId, Set<String> keys, UUID correlationId) {
        this.workspaceId = Objects.requireNonNull(workspaceId);
        this.correlationId = Objects.requireNonNull(correlationId);
        this.keys = Set.copyOf(keys);
        if (keys.isEmpty() || keys.size() > 100 || keys.stream().anyMatch(k -> !k.matches("T-(?:00[1-9]|0[1-9][0-9]|[1-9][0-9]{2,3})")))
            throw new IllegalArgumentException("Invalid task keys");
    }
    public UUID workspaceId() { return workspaceId; }
    public UUID correlationId() { return correlationId; }
    public Set<String> keys() { return keys; }
    public synchronized void respond(Map<String, Resolution> result) {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Synchronous response required");
        responses++;
        resolutions = Map.copyOf(result);
    }
    public synchronized Map<String, Resolution> requireResolutions() {
        if (responses != 1 || resolutions == null || !resolutions.keySet().equals(keys))
            throw new IllegalStateException("Exactly one complete resolution required");
        Set<UUID> ids = new java.util.HashSet<>();
        resolutions.forEach((key, value) -> {
            if (!workspaceId.equals(value.workspaceId()) || !key.equals(value.key())
                    || (value.taskId() != null && !ids.add(value.taskId())))
                throw new IllegalStateException("Invalid resolution scope");
        });
        return resolutions;
    }
    @Override public String eventType() { return EventTypes.TASK_REFERENCES_RESOLVE_REQUESTED; }
    @Override public Instant occurredAt() { return occurredAt; }
}
