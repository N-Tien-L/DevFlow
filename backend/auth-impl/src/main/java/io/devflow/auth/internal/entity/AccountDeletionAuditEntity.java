package io.devflow.auth.internal.entity;

import io.devflow.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.ColumnDefault;

/**
 * Audit record that an account was erased (T-009). Holds no PII: the user id of the erased account (no longer
 * resolvable), when it happened, the truncated client IP and how many sole-owned workspaces went with it.
 */
@Entity
@Table(name = "account_deletion_audit")
public class AccountDeletionAuditEntity extends BaseEntity {

    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    @Column(name = "deleted_at", nullable = false)
    private Instant deletedAt;

    @Column(name = "client_ip_masked", length = 45)
    private String clientIpMasked;

    // Same DEFAULT as the migration, so schemas generated from entities accept inserts without the column.
    @ColumnDefault("0")
    @Column(name = "workspaces_deleted", nullable = false)
    private int workspacesDeleted;

    protected AccountDeletionAuditEntity() {
        // Required by JPA
    }

    public AccountDeletionAuditEntity(UUID userId, Instant deletedAt, String clientIpMasked, int workspacesDeleted) {
        this.userId = userId;
        this.deletedAt = deletedAt;
        this.clientIpMasked = clientIpMasked;
        this.workspacesDeleted = workspacesDeleted;
    }

    public UUID getUserId() {
        return userId;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public String getClientIpMasked() {
        return clientIpMasked;
    }

    public int getWorkspacesDeleted() {
        return workspacesDeleted;
    }
}
