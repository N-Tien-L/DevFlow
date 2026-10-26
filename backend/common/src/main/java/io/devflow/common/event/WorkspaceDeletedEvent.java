package io.devflow.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code workspace.deleted} - published by Auth after a workspace is deleted (today: a workspace whose only
 * member erased their account, T-009). Consumed by Board, which deletes the workspace's boards and their
 * columns, tasks and comments (other modules only hold soft references to the workspace id) -
 * ARCHITECTURE.md Section 4.
 *
 * <p>Published inside the Auth transaction, so a consumer can record durable work atomically with the deletion;
 * consumers must be idempotent.
 *
 * @param workspaceId     id of the deleted workspace
 * @param deletedByUserId the account whose deletion removed it; its in-flight writes must finish before cleanup
 */
public record WorkspaceDeletedEvent(UUID workspaceId, UUID deletedByUserId, Instant occurredAt)
        implements DevFlowEvent {

    public WorkspaceDeletedEvent(UUID workspaceId, UUID deletedByUserId) {
        this(workspaceId, deletedByUserId, Instant.now());
    }

    @Override
    public String eventType() {
        return EventTypes.WORKSPACE_DELETED;
    }
}
