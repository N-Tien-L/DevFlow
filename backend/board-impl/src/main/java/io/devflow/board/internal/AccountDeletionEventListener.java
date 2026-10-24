package io.devflow.board.internal;

import io.devflow.board.internal.repository.BoardAuditRepository;
import io.devflow.board.internal.repository.BoardRepository;
import io.devflow.board.internal.repository.CommentRepository;
import io.devflow.board.internal.repository.TaskRepository;
import io.devflow.common.event.DeletedUser;
import io.devflow.common.event.UserAccountDeletedEvent;
import io.devflow.common.event.WorkspaceDeletedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Board's part of account erasure (T-009, ARCHITECTURE.md Section 4).
 *
 * <p>{@code user.account_deleted}: tasks assigned to the user become unassigned, and the user is replaced by
 * {@link DeletedUser#ID} as comment author and audit actor (audit client IPs are dropped). Comment content and
 * audit history stay: they belong to the team. {@code workspace.deleted}: the workspace's boards (with their
 * columns, tasks, comments and tags) and its audit rows are deleted.
 *
 * <p>Both run after Auth's transaction commits, each in its own transaction, and are idempotent (re-delivery
 * matches no rows). A failure is logged and counted but not rethrown: Auth has already committed, and throwing
 * from an after-commit listener would turn the user's successful deletion into an error response.
 */
@Component
public class AccountDeletionEventListener {

    private static final Logger log = LoggerFactory.getLogger(AccountDeletionEventListener.class);

    private final TaskRepository taskRepository;
    private final CommentRepository commentRepository;
    private final BoardAuditRepository auditRepository;
    private final BoardRepository boardRepository;
    private final TransactionTemplate transactionTemplate;
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;
    private final Clock clock;

    public AccountDeletionEventListener(
            TaskRepository taskRepository,
            CommentRepository commentRepository,
            BoardAuditRepository auditRepository,
            BoardRepository boardRepository,
            PlatformTransactionManager transactionManager,
            ObjectProvider<MeterRegistry> meterRegistryProvider,
            ObjectProvider<Clock> clockProvider) {
        this.taskRepository = taskRepository;
        this.commentRepository = commentRepository;
        this.auditRepository = auditRepository;
        this.boardRepository = boardRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.meterRegistryProvider = meterRegistryProvider;
        this.clock = clockProvider.getIfAvailable(Clock::systemUTC);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(UserAccountDeletedEvent event) {
        run("user", () -> {
            int tasks = taskRepository.unassignUser(event.userId(), clock.instant());
            int comments = commentRepository.replaceAuthor(event.userId(), DeletedUser.ID);
            int audits = auditRepository.anonymiseActor(event.userId(), DeletedUser.ID);
            return "user " + event.userId() + ": " + tasks + " tasks unassigned, " + comments
                    + " comments and " + audits + " audit rows anonymised";
        });
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(WorkspaceDeletedEvent event) {
        run("workspace", () -> {
            int audits = auditRepository.deleteByWorkspaceId(event.workspaceId());
            int boards = boardRepository.deleteByWorkspaceId(event.workspaceId());
            return "workspace " + event.workspaceId() + ": " + boards + " boards and " + audits
                    + " audit rows deleted";
        });
    }

    private void run(String kind, Supplier<String> cleanup) {
        try {
            String summary = transactionTemplate.execute(status -> cleanup.get());
            count(kind, "success");
            log.info("Account deletion cleanup for {}", summary);
        } catch (RuntimeException e) {
            count(kind, "failed");
            // Exception text is not logged: it may echo SQL parameters.
            log.error("Account deletion cleanup ({}) failed: {}", kind, e.getClass().getSimpleName());
        }
    }

    private void count(String kind, String result) {
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry != null) {
            registry.counter("devflow.board.account_cleanup", "kind", kind, "result", result).increment();
        }
    }
}
