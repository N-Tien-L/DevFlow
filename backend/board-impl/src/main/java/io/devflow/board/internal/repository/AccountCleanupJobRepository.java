package io.devflow.board.internal.repository;

import io.devflow.board.internal.entity.AccountCleanupJobEntity;
import io.devflow.board.internal.entity.AccountCleanupKind;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Durable Board cleanup jobs left by account and workspace deletions (T-009). */
@Repository
public interface AccountCleanupJobRepository extends JpaRepository<AccountCleanupJobEntity, UUID> {

    Optional<AccountCleanupJobEntity> findByKindAndSubjectId(AccountCleanupKind kind, UUID subjectId);

    @Query("select job.id from AccountCleanupJobEntity job where job.nextAttemptAt <= :now order by job.nextAttemptAt")
    List<UUID> findDueIds(@Param("now") Instant now, Pageable page);

    /**
     * Locks the job for processing, skipping it when another thread or instance holds it (empty result).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select job from AccountCleanupJobEntity job where job.id = :id")
    Optional<AccountCleanupJobEntity> claim(@Param("id") UUID id);
}
