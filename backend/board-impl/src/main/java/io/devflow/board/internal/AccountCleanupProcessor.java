package io.devflow.board.internal;

import io.devflow.board.internal.entity.AccountCleanupJobEntity;
import io.devflow.board.internal.entity.AccountCleanupKind;
import io.devflow.board.internal.repository.AccountCleanupJobRepository;
import io.devflow.board.internal.repository.BoardAuditRepository;
import io.devflow.board.internal.repository.BoardRepository;
import io.devflow.board.internal.repository.CommentRepository;
import io.devflow.board.internal.repository.TaskRepository;
import io.devflow.common.event.DeletedUser;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs Board's account-deletion cleanup jobs (T-009) and retries the ones that failed.
 *
 * <p>A job is claimed with {@code FOR UPDATE SKIP LOCKED}, so the immediate after-commit attempt and the
 * scheduled retry (on any instance) never run it twice at once. The cleanup takes the deleted user's
 * {@link UserWriteLock} exclusively first, so it also covers Board writes that were already past their membership
 * check. The job row is deleted in the cleanup's own transaction: no row, no work left. A failure is recorded on
 * the row and retried with exponential backoff (30 s doubling, capped at 1 h) until it succeeds.
 *
 * <p>Metrics: {@code devflow.board.account_cleanup{kind,result=success|failed}} and the gauge
 * {@code devflow.board.account_cleanup.pending} (jobs not yet done).
 */
@Component
public class AccountCleanupProcessor {

    static final Duration FIRST_RETRY_DELAY = Duration.ofSeconds(30);
    static final Duration MAX_RETRY_DELAY = Duration.ofHours(1);
    private static final int RETRY_BATCH = 50;

    private static final Logger log = LoggerFactory.getLogger(AccountCleanupProcessor.class);

    private final AccountCleanupJobRepository jobRepository;
    private final TaskRepository taskRepository;
    private final CommentRepository commentRepository;
    private final BoardAuditRepository auditRepository;
    private final BoardRepository boardRepository;
    private final UserWriteLock userWriteLock;
    private final TransactionTemplate transactionTemplate;
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;
    private final Clock clock;
    private final AtomicLong pending = new AtomicLong();
    private volatile boolean gaugeRegistered;

    public AccountCleanupProcessor(
            AccountCleanupJobRepository jobRepository,
            TaskRepository taskRepository,
            CommentRepository commentRepository,
            BoardAuditRepository auditRepository,
            BoardRepository boardRepository,
            UserWriteLock userWriteLock,
            PlatformTransactionManager transactionManager,
            ObjectProvider<MeterRegistry> meterRegistryProvider,
            ObjectProvider<Clock> clockProvider) {
        this.jobRepository = jobRepository;
        this.taskRepository = taskRepository;
        this.commentRepository = commentRepository;
        this.auditRepository = auditRepository;
        this.boardRepository = boardRepository;
        this.userWriteLock = userWriteLock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.meterRegistryProvider = meterRegistryProvider;
        this.clock = clockProvider.getIfAvailable(Clock::systemUTC);
    }

    /**
     * Records a job in the caller's transaction (Auth's deletion), so it commits or rolls back with the deletion.
     * Re-delivery of the same event is a no-op.
     */
    public void enqueue(AccountCleanupKind kind, UUID subjectId, UUID lockUserId) {
        if (jobRepository.findByKindAndSubjectId(kind, subjectId).isEmpty()) {
            jobRepository.save(new AccountCleanupJobEntity(kind, subjectId, lockUserId, clock.instant()));
        }
    }

    /**
     * Runs the job for this subject now, if there still is one. Never throws: a failure is recorded for retry.
     */
    public void runNow(AccountCleanupKind kind, UUID subjectId) {
        Optional<UUID> jobId;
        try {
            jobId = jobRepository.findByKindAndSubjectId(kind, subjectId).map(AccountCleanupJobEntity::getId);
        } catch (RuntimeException e) {
            log.error("Could not load {} cleanup job: {}; the scheduled retry will pick it up",
                    kind, e.getClass().getSimpleName());
            return;
        }
        jobId.ifPresent(this::run);
    }

    /**
     * Retries every job that is due, oldest first.
     */
    @Scheduled(initialDelayString = "${devflow.board.account-cleanup.retry-delay:PT1M}",
            fixedDelayString = "${devflow.board.account-cleanup.retry-delay:PT1M}")
    public void retryDue() {
        try {
            List<UUID> due = jobRepository.findDueIds(clock.instant(), PageRequest.of(0, RETRY_BATCH));
            due.forEach(this::run);
            updatePending();
        } catch (RuntimeException e) {
            log.error("Account cleanup retry pass failed: {}", e.getClass().getSimpleName());
        }
    }

    private void run(UUID jobId) {
        AccountCleanupKind[] kind = new AccountCleanupKind[1];
        try {
            String summary = transactionTemplate.execute(status -> {
                Optional<AccountCleanupJobEntity> claimed = jobRepository.claim(jobId);
                if (claimed.isEmpty()) {
                    return null; // done meanwhile, or being run elsewhere
                }
                AccountCleanupJobEntity job = claimed.get();
                kind[0] = job.getKind();
                userWriteLock.lockExclusive(job.getLockUserId());
                String result = cleanup(job);
                jobRepository.delete(job);
                return result;
            });
            if (summary != null) {
                count(kind[0], "success");
                log.info("Account deletion cleanup done for {}", summary);
            }
        } catch (RuntimeException e) {
            count(kind[0], "failed");
            recordFailure(jobId, e);
        }
        updatePending();
    }

    private String cleanup(AccountCleanupJobEntity job) {
        UUID subject = job.getSubjectId();
        return switch (job.getKind()) {
            case USER -> {
                int tasks = taskRepository.unassignUser(subject, clock.instant());
                int comments = commentRepository.replaceAuthor(subject, DeletedUser.ID);
                int audits = auditRepository.anonymiseActor(subject, DeletedUser.ID);
                yield "user " + subject + ": " + tasks + " tasks unassigned, " + comments
                        + " comments and " + audits + " audit rows anonymised";
            }
            case WORKSPACE -> {
                int audits = auditRepository.deleteByWorkspaceId(subject);
                int boards = boardRepository.deleteByWorkspaceId(subject);
                yield "workspace " + subject + ": " + boards + " boards and " + audits + " audit rows deleted";
            }
        };
    }

    private void recordFailure(UUID jobId, RuntimeException failure) {
        String errorType = failure.getClass().getSimpleName();
        try {
            Integer attempts = transactionTemplate.execute(status -> jobRepository.findById(jobId).map(job -> {
                job.recordFailure(clock.instant(), retryDelay(job.getAttempts() + 1), errorType);
                return job.getAttempts();
            }).orElse(null));
            // Exception text is not logged: it may echo SQL parameters.
            log.error("Account deletion cleanup job {} failed (attempt {}): {}; will retry",
                    jobId, attempts, errorType);
        } catch (RuntimeException e) {
            log.error("Account deletion cleanup job {} failed ({}) and the failure could not be recorded ({}); "
                    + "it stays due and is retried", jobId, errorType, e.getClass().getSimpleName());
        }
    }

    static Duration retryDelay(int attempts) {
        int doublings = Math.min(Math.max(attempts - 1, 0), 20);
        Duration delay = FIRST_RETRY_DELAY.multipliedBy(1L << doublings);
        return delay.compareTo(MAX_RETRY_DELAY) > 0 ? MAX_RETRY_DELAY : delay;
    }

    private void count(AccountCleanupKind kind, String result) {
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry != null) {
            registry.counter("devflow.board.account_cleanup",
                    "kind", kind == null ? "unknown" : kind.name().toLowerCase(Locale.ROOT), "result", result).increment();
        }
    }

    private void updatePending() {
        try {
            pending.set(jobRepository.count());
        } catch (RuntimeException e) {
            return;
        }
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry != null && !gaugeRegistered) {
            Gauge.builder("devflow.board.account_cleanup.pending", pending, AtomicLong::get)
                    .description("Board account-deletion cleanup jobs not yet done")
                    .register(registry);
            gaugeRegistered = true;
        }
    }
}
