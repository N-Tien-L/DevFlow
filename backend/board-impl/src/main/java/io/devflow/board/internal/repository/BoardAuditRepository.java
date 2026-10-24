package io.devflow.board.internal.repository;

import io.devflow.board.internal.entity.BoardAuditEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface BoardAuditRepository extends JpaRepository<BoardAuditEntity, UUID> {

    /**
     * Anonymises a deleted user's audit rows (T-009): the actor becomes the placeholder and the client IP is
     * dropped; what happened to which resource is kept.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update BoardAuditEntity audit set audit.actorId = :replacement, audit.clientIp = null
            where audit.actorId = :userId
            """)
    int anonymiseActor(@Param("userId") UUID userId, @Param("replacement") UUID replacement);

    /** Removes the audit trail of a deleted workspace (T-009). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from BoardAuditEntity audit where audit.workspaceId = :workspaceId")
    int deleteByWorkspaceId(@Param("workspaceId") UUID workspaceId);
}
