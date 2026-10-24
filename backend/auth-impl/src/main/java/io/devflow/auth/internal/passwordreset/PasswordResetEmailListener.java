package io.devflow.auth.internal.passwordreset;

import io.devflow.auth.internal.support.AuthMailExecutor;
import io.devflow.auth.internal.support.AuthMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Dispatches password reset emails off the request thread and only after commit, so the forgot-password
 * response takes the same time whether or not an email is sent.
 */
@Component
public class PasswordResetEmailListener {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetEmailListener.class);

    private final PasswordResetEmailSender sender;
    private final AuthMailExecutor executor;
    private final AuthMetrics metrics;

    public PasswordResetEmailListener(PasswordResetEmailSender sender, AuthMailExecutor executor,
                                      AuthMetrics metrics) {
        this.sender = sender;
        this.executor = executor;
        this.metrics = metrics;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onResetRequested(PasswordResetEmailRequestedEvent event) {
        if (!executor.submit(() -> sender.sendResetLink(event))) {
            metrics.passwordResetEmail(PasswordResetEmailSender.KIND_RESET_LINK, "rejected");
            log.error("Password reset email for user {} rejected: mail executor saturated", event.userId());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPasswordChanged(PasswordChangedEmailRequestedEvent event) {
        if (!executor.submit(() -> sender.sendPasswordChanged(event))) {
            metrics.passwordResetEmail(PasswordResetEmailSender.KIND_PASSWORD_CHANGED, "rejected");
            log.error("Password changed notice for user {} rejected: mail executor saturated", event.userId());
        }
    }
}
