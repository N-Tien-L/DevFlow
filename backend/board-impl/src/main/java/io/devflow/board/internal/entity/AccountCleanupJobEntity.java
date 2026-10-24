package io.devflow.board.internal.entity;

import io.devflow.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.ColumnDefault;

/**
 * Durable Board cleanup still to do after an account or workspace deletion (T-009). Written in the transaction
 * that deletes the user, deleted once the cleanup has committed, retried with backoff until then.
 */
@Entity
@Table(name = "board_account_cleanup_jobs")
public class AccountCleanupJobEntity extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private AccountCleanupKind kind;

    @Column(name = "subject_id", nullable = false)
    private UUID subjectId;

    /** The deleted account whose in-flight Board writes must finish before this cleanup runs. */
    @Column(name = "lock_user_id", nullable = false)
    private UUID lockUserId;

    // Defaults match the migration, so schemas generated from entities accept inserts without these columns.
    @ColumnDefault("0")
    @Column(name = "attempts", nullable = false)
    private int attempts;

    @ColumnDefault("CURRENT_TIMESTAMP")
    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = 100)
    private String lastError;

    protected AccountCleanupJobEntity() {
        // Required by JPA
    }

    public AccountCleanupJobEntity(AccountCleanupKind kind, UUID subjectId, UUID lockUserId, Instant dueAt) {
        this.kind = kind;
        this.subjectId = subjectId;
        this.lockUserId = lockUserId;
        this.nextAttemptAt = dueAt;
    }

    /**
     * Records a failed attempt and schedules the next one {@code delay} after {@code now}.
     *
     * @param errorType exception type only; messages may echo SQL parameters
     */
    public void recordFailure(Instant now, Duration delay, String errorType) {
        this.attempts++;
        this.nextAttemptAt = now.plus(delay);
        this.lastError = errorType.length() > 100 ? errorType.substring(0, 100) : errorType;
    }

    public AccountCleanupKind getKind() {
        return kind;
    }

    public UUID getSubjectId() {
        return subjectId;
    }

    public UUID getLockUserId() {
        return lockUserId;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }
}
