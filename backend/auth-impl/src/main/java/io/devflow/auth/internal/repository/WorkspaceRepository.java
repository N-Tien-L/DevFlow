package io.devflow.auth.internal.repository;

import io.devflow.auth.internal.entity.WorkspaceEntity;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

    /**
     * Loads a workspace with a row lock, serialising concurrent invitations into it so the
     * pending-invitation quota and the one-open-invitation-per-email rule cannot be raced.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM WorkspaceEntity w WHERE w.id = :id")
    Optional<WorkspaceEntity> findByIdForUpdate(@Param("id") UUID id);
}
