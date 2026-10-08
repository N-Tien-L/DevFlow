package io.devflow.board.internal.entity;

import io.devflow.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** Immutable, Board-owned audit record. Auth ids are deliberately soft references. */
@Entity
@Table(name = "board_audit_events")
public class BoardAuditEntity extends BaseEntity {

    @Column(name = "actor_id", nullable = false)
    private UUID actorId;

    @Column(name = "action", nullable = false, length = 32)
    private String action;

    @Column(name = "resource_type", nullable = false, length = 16)
    private String resourceType;

    @Column(name = "resource_id", nullable = false)
    private UUID resourceId;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "correlation_id", nullable = false)
    private UUID correlationId;

    @Column(name = "client_ip", length = 45)
    private String clientIp;

    /** JSON array of changed field names; values are omitted to avoid copying user content. */
    @Column(name = "changed_fields", nullable = false, columnDefinition = "text")
    private String changedFields;

    @Column(name = "source_column_id")
    private UUID sourceColumnId;

    @Column(name = "destination_column_id")
    private UUID destinationColumnId;

    protected BoardAuditEntity() {
        // Required by JPA.
    }

    public BoardAuditEntity(
            UUID actorId,
            String action,
            String resourceType,
            UUID resourceId,
            UUID workspaceId,
            UUID correlationId,
            String clientIp,
            String changedFields) {
        this(actorId, action, resourceType, resourceId, workspaceId, correlationId, clientIp,
                changedFields, null, null);
    }

    public BoardAuditEntity(
            UUID actorId,
            String action,
            String resourceType,
            UUID resourceId,
            UUID workspaceId,
            UUID correlationId,
            String clientIp,
            String changedFields,
            UUID sourceColumnId,
            UUID destinationColumnId) {
        this.actorId = actorId;
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.workspaceId = workspaceId;
        this.correlationId = correlationId;
        this.clientIp = clientIp;
        this.changedFields = changedFields;
        this.sourceColumnId = sourceColumnId;
        this.destinationColumnId = destinationColumnId;
    }

    public UUID getActorId() {
        return actorId;
    }

    public String getAction() {
        return action;
    }

    public String getResourceType() {
        return resourceType;
    }

    public UUID getResourceId() {
        return resourceId;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public UUID getCorrelationId() {
        return correlationId;
    }

    public String getClientIp() {
        return clientIp;
    }

    public String getChangedFields() {
        return changedFields;
    }

    public UUID getSourceColumnId() {
        return sourceColumnId;
    }

    public UUID getDestinationColumnId() {
        return destinationColumnId;
    }
}
