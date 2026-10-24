package io.devflow.auth.internal.repository;

import io.devflow.auth.internal.entity.WorkspaceMemberEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link WorkspaceMemberEntity}.
 */
@Repository
public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMemberEntity, UUID> {

    Optional<WorkspaceMemberEntity> findByWorkspaceIdAndUserId(UUID workspaceId, UUID userId);

    boolean existsByWorkspaceIdAndUserId(UUID workspaceId, UUID userId);

    List<WorkspaceMemberEntity> findAllByWorkspaceId(UUID workspaceId);

    Page<WorkspaceMemberEntity> findAllByWorkspace_IdAndUser_FullNameContainingIgnoreCaseOrderByUser_FullNameAscUser_IdAsc(
            UUID workspaceId, String fullName, Pageable pageable);

    List<WorkspaceMemberEntity> findAllByUserId(UUID userId);

    long countByWorkspaceIdAndUserIdNot(UUID workspaceId, UUID userId);
}
