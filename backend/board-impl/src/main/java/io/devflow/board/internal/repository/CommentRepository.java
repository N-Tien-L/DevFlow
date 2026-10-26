package io.devflow.board.internal.repository;

import io.devflow.board.internal.entity.CommentEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Persistence queries for comments in chronological order within their task. */
@Repository
public interface CommentRepository extends JpaRepository<CommentEntity, UUID> {

    List<CommentEntity> findByTask_IdOrderByCreatedAtAscIdAsc(UUID taskId);

    Page<CommentEntity> findAllByTask_Id(UUID taskId, Pageable pageable);

    Optional<CommentEntity> findByIdAndTask_Id(UUID id, UUID taskId);

    /**
     * Replaces a deleted user as comment author (T-009); the content stays, it belongs to the team's discussion.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update CommentEntity comment set comment.authorId = :replacement where comment.authorId = :userId")
    int replaceAuthor(@Param("userId") UUID userId, @Param("replacement") UUID replacement);
}
