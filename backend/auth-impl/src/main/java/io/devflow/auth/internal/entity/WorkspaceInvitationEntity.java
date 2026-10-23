package io.devflow.auth.internal.entity;

import io.devflow.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * JPA entity for a single-use, expiring invitation to join a workspace.
 * Only the SHA-256 hex digest of the token is persisted; the raw token is delivered by email.
 */
@Entity
@Table(name = "workspace_invitations")
public class WorkspaceInvitationEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private WorkspaceEntity workspace;

    @Column(name = "email", nullable = false, length = 254)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private WorkspaceRole role;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private InvitationStatus status = InvitationStatus.PENDING;

    @Column(name = "invited_by")
    private UUID invitedBy;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "accepted_by")
    private UUID acceptedBy;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected WorkspaceInvitationEntity() {
        // Required by JPA
    }

    public WorkspaceInvitationEntity(WorkspaceEntity workspace, String email, WorkspaceRole role,
                                     String tokenHash, UUID invitedBy, Instant expiresAt) {
        this.workspace = workspace;
        this.email = email;
        this.role = role;
        this.tokenHash = tokenHash;
        this.invitedBy = invitedBy;
        this.expiresAt = expiresAt;
    }

    public WorkspaceEntity getWorkspace() {
        return workspace;
    }

    public String getEmail() {
        return email;
    }

    public WorkspaceRole getRole() {
        return role;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public InvitationStatus getStatus() {
        return status;
    }

    public UUID getInvitedBy() {
        return invitedBy;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }

    public UUID getAcceptedBy() {
        return acceptedBy;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public boolean isPending() {
        return status == InvitationStatus.PENDING;
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }
}
