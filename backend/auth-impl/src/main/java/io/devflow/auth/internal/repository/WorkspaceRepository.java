package io.devflow.auth.internal.repository;

import io.devflow.auth.internal.entity.WorkspaceEntity;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link WorkspaceEntity}.
 */
@Repository
public interface WorkspaceRepository extends JpaRepository<WorkspaceEntity, UUID> {

    Optional<WorkspaceEntity> findBySlug(String slug);

    boolean existsBySlug(String slug);

    List<WorkspaceEntity> findAllByOwnerId(UUID ownerId);

    long countByOwnerId(UUID ownerId);

    /** The owner's id without loading the workspace into the persistence context. */
    @Query("SELECT w.owner.id FROM WorkspaceEntity w WHERE w.id = :id")
    Optional<UUID> findOwnerIdById(@Param("id") UUID id);

    /**
     * Workspaces owned by a user, row-locked so members cannot be added or removed while the account is deleted.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM WorkspaceEntity w WHERE w.owner.id = :ownerId ORDER BY w.createdAt")
    List<WorkspaceEntity> findAllByOwnerIdForUpdate(@Param("ownerId") UUID ownerId);

    /**
     * Bulk delete; members and invitations go with them through {@code ON DELETE CASCADE}.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM WorkspaceEntity w WHERE w.id IN :ids")
    int deleteAllByIdIn(@Param("ids") List<UUID> ids);

    /**
     * Loads a workspace with a row lock, serialising concurrent invitations into it so the
     * pending-invitation quota and the one-open-invitation-per-email rule cannot be raced.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM WorkspaceEntity w WHERE w.id = :id")
    Optional<WorkspaceEntity> findByIdForUpdate(@Param("id") UUID id);
}
