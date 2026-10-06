package io.devflow.board.internal.repository;

import io.devflow.board.internal.entity.BoardEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Persistence queries for boards, always supporting workspace-scoped reads. */
@Repository
public interface BoardRepository extends JpaRepository<BoardEntity, UUID> {

    List<BoardEntity> findByWorkspaceIdOrderByCreatedAtAscIdAsc(UUID workspaceId);

    List<BoardEntity> findByWorkspaceIdAndArchivedFalseOrderByCreatedAtAscIdAsc(UUID workspaceId);

    Optional<BoardEntity> findByIdAndWorkspaceId(UUID id, UUID workspaceId);
}
