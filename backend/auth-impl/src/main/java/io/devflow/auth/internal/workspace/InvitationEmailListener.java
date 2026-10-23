package io.devflow.auth.internal.workspace;

import io.devflow.auth.internal.support.AuthMailExecutor;
import io.devflow.auth.internal.support.AuthMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Dispatches invitation emails off the request thread, and only after the invitation is committed
 * (a rolled-back invitation never sends mail). When the mail pool is saturated the task is dropped and
 * counted; the inviter can revoke and invite again.
 */
@Component
public class InvitationEmailListener {

    private static final Logger log = LoggerFactory.getLogger(InvitationEmailListener.class);

    private final InvitationEmailSender sender;
    private final AuthMailExecutor executor;
    private final AuthMetrics metrics;

    public InvitationEmailListener(InvitationEmailSender sender, AuthMailExecutor executor, AuthMetrics metrics) {
        this.sender = sender;
        this.executor = executor;
        this.metrics = metrics;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onInvitationEmailRequested(InvitationEmailRequestedEvent event) {
        if (!executor.submit(() -> sender.send(event))) {
            metrics.workspaceInvitationEmail("rejected");
            log.error("Invitation email task for invitation {} rejected: mail executor saturated",
                    event.invitationId());
        }
    }
}
