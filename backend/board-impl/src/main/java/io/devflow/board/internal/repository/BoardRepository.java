package io.devflow.board.internal.repository;

import io.devflow.board.internal.entity.BoardEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import jakarta.persistence.LockModeType;

/** Persistence queries for boards, always supporting workspace-scoped reads. */
@Repository
public interface BoardRepository extends JpaRepository<BoardEntity, UUID> {

    List<BoardEntity> findByWorkspaceIdOrderByCreatedAtAscIdAsc(UUID workspaceId);

    List<BoardEntity> findByWorkspaceIdAndArchivedFalseOrderByCreatedAtAscIdAsc(UUID workspaceId);

    Optional<BoardEntity> findByIdAndWorkspaceId(UUID id, UUID workspaceId);

    @Query("select board.workspaceId from BoardEntity board where board.id = :boardId")
    Optional<UUID> findWorkspaceIdByBoardId(@Param("boardId") UUID boardId);

    Page<BoardEntity> findByWorkspaceId(UUID workspaceId, Pageable pageable);

    Page<BoardEntity> findByWorkspaceIdAndArchivedFalse(UUID workspaceId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select board from BoardEntity board where board.id = :boardId")
    Optional<BoardEntity> findByIdForUpdate(@Param("boardId") UUID boardId);

    /**
     * Deletes every board of a deleted workspace (T-009); columns, tasks, comments and tags go with them through
     * {@code ON DELETE CASCADE}.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from BoardEntity board where board.workspaceId = :workspaceId")
    int deleteByWorkspaceId(@Param("workspaceId") UUID workspaceId);
}
