package io.devflow.auth.internal.repository;

import io.devflow.auth.internal.entity.InvitationStatus;
import io.devflow.auth.internal.entity.WorkspaceInvitationEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link WorkspaceInvitationEntity}.
 */
@Repository
public interface WorkspaceInvitationRepository extends JpaRepository<WorkspaceInvitationEntity, UUID> {

    @Query("SELECT i FROM WorkspaceInvitationEntity i JOIN FETCH i.workspace WHERE i.tokenHash = :tokenHash")
    Optional<WorkspaceInvitationEntity> findByTokenHash(@Param("tokenHash") String tokenHash);

    Optional<WorkspaceInvitationEntity> findByWorkspaceIdAndEmailAndStatus(
            UUID workspaceId, String email, InvitationStatus status);

    long countByWorkspaceIdAndStatusAndExpiresAtAfter(UUID workspaceId, InvitationStatus status, Instant now);

    List<WorkspaceInvitationEntity> findAllByWorkspaceIdAndStatusAndExpiresAtAfterOrderByCreatedAtDesc(
            UUID workspaceId, InvitationStatus status, Instant now);

    /**
     * Atomically consumes a pending, unexpired invitation. Returns 0 when it was already accepted,
     * revoked or expired, which makes concurrent accepts of the same link safe.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE WorkspaceInvitationEntity i SET i.status = io.devflow.auth.internal.entity.InvitationStatus.ACCEPTED, "
            + "i.acceptedAt = :now, i.acceptedBy = :userId, i.updatedAt = :now "
            + "WHERE i.id = :id AND i.status = io.devflow.auth.internal.entity.InvitationStatus.PENDING "
            + "AND i.expiresAt > :now")
    int markAccepted(@Param("id") UUID id, @Param("userId") UUID userId, @Param("now") Instant now);

    /**
     * Revokes a pending invitation, scoped to its workspace so an id from another workspace never matches.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE WorkspaceInvitationEntity i SET i.status = io.devflow.auth.internal.entity.InvitationStatus.REVOKED, "
            + "i.revokedAt = :now, i.updatedAt = :now "
            + "WHERE i.id = :id AND i.workspace.id = :workspaceId "
            + "AND i.status = io.devflow.auth.internal.entity.InvitationStatus.PENDING")
    int markRevoked(@Param("id") UUID id, @Param("workspaceId") UUID workspaceId, @Param("now") Instant now);

    /**
     * Purges invitations that expired or were revoked before the cutoff (accepted ones go once expired).
     */
    @Modifying
    @Query("DELETE FROM WorkspaceInvitationEntity i WHERE i.expiresAt < :cutoff OR i.revokedAt < :cutoff")
    int deleteStaleInvitations(@Param("cutoff") Instant cutoff);
}
