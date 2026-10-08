package io.devflow.common.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Synchronous request/decision contract for checking workspace membership across modules.
 *
 * <p>The publisher and the Auth listener run on the same request thread. Auth records exactly
 * one decision on this event before {@link org.springframework.context.ApplicationEventPublisher}
 * returns. An unresolved or multiply resolved event must fail closed. This control event must
 * never be handled asynchronously because the caller needs the decision before it can write.
 */
public final class WorkspaceMembershipCheckRequestedEvent implements DevFlowEvent {

    public enum Decision {
        PENDING,
        MEMBER,
        NOT_MEMBER,
        AMBIGUOUS
    }

    private final UUID correlationId;
    private final UUID userId;
    private final UUID workspaceId;
    private final Instant occurredAt;
    private Decision decision = Decision.PENDING;
    private int responseCount;

    public WorkspaceMembershipCheckRequestedEvent(UUID correlationId, UUID userId, UUID workspaceId) {
        this.correlationId = Objects.requireNonNull(correlationId, "correlationId must not be null");
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        this.occurredAt = Instant.now();
    }

    public UUID correlationId() {
        return correlationId;
    }

    public UUID userId() {
        return userId;
    }

    public UUID workspaceId() {
        return workspaceId;
    }

    public synchronized Decision decision() {
        return decision;
    }

    public synchronized int responseCount() {
        return responseCount;
    }

    /** Called by the owning Auth module's synchronous listener. */
    public synchronized void recordDecision(boolean isMember) {
        responseCount++;
        if (responseCount == 1) {
            decision = isMember ? Decision.MEMBER : Decision.NOT_MEMBER;
        } else {
            decision = Decision.AMBIGUOUS;
        }
    }

    @Override
    public String eventType() {
        return EventTypes.WORKSPACE_MEMBERSHIP_CHECK_REQUESTED;
    }

    @Override
    public Instant occurredAt() {
        return occurredAt;
    }
}
