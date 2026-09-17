package io.devflow.auth.internal.repository;

import io.devflow.auth.internal.entity.WorkspaceEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link WorkspaceEntity}.
 */
@Repository
public interface WorkspaceRepository extends JpaRepository<WorkspaceEntity, UUID> {

    Optional<WorkspaceEntity> findBySlug(String slug);

    boolean existsBySlug(String slug);

    List<WorkspaceEntity> findAllByOwnerId(UUID ownerId);
}
