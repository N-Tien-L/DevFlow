package io.devflow.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code workspace.deleted} - published by Auth after a workspace is deleted (today: a workspace whose only
 * member erased their account, T-009). Consumed by Board, which deletes the workspace's boards and their
 * columns, tasks and comments (other modules only hold soft references to the workspace id) -
 * ARCHITECTURE.md Section 4.
 *
 * <p>Delivered after the Auth transaction commits; consumers must be idempotent.
 *
 * @param workspaceId id of the deleted workspace
 */
public record WorkspaceDeletedEvent(UUID workspaceId, Instant occurredAt) implements DevFlowEvent {

    public WorkspaceDeletedEvent(UUID workspaceId) {
        this(workspaceId, Instant.now());
    }

    @Override
    public String eventType() {
        return EventTypes.WORKSPACE_DELETED;
    }
}
