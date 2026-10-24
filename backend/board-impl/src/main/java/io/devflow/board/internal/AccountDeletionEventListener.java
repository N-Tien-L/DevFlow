package io.devflow.board.internal;

import io.devflow.board.internal.entity.AccountCleanupKind;
import io.devflow.common.event.DeletedUser;
import io.devflow.common.event.UserAccountDeletedEvent;
import io.devflow.common.event.WorkspaceDeletedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Board's part of account erasure (T-009, ARCHITECTURE.md Section 4).
 *
 * <p>{@code user.account_deleted}: tasks assigned to the user become unassigned, and the user is replaced by
 * {@link DeletedUser#ID} as comment author and audit actor (audit client IPs are dropped). Comment content and
 * audit history stay: they belong to the team. {@code workspace.deleted}: the workspace's boards (with their
 * columns, tasks, comments and tags) and its audit rows are deleted.
 *
 * <p>Each event first records a durable job inside Auth's transaction, so the work commits or rolls back with the
 * deletion and cannot be lost. After commit the job runs right away; if that fails, the
 * {@link AccountCleanupProcessor} retries it on a schedule until it succeeds. All of it is idempotent.
 */
@Component
public class AccountDeletionEventListener {

    private final AccountCleanupProcessor processor;

    public AccountDeletionEventListener(AccountCleanupProcessor processor) {
        this.processor = processor;
    }

    /** Inside Auth's deletion transaction. */
    @EventListener
    public void record(UserAccountDeletedEvent event) {
        processor.enqueue(AccountCleanupKind.USER, event.userId(), event.userId());
    }

    /** Inside Auth's deletion transaction. */
    @EventListener
    public void record(WorkspaceDeletedEvent event) {
        processor.enqueue(AccountCleanupKind.WORKSPACE, event.workspaceId(), event.deletedByUserId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(UserAccountDeletedEvent event) {
        processor.runNow(AccountCleanupKind.USER, event.userId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(WorkspaceDeletedEvent event) {
        processor.runNow(AccountCleanupKind.WORKSPACE, event.workspaceId());
    }
}
