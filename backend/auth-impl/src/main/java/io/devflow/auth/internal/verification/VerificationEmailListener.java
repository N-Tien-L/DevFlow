package io.devflow.auth.internal.verification;

import io.devflow.auth.internal.support.AuthMailExecutor;
import io.devflow.auth.internal.support.AuthMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Dispatches verification emails off the request thread, and only after the issuing transaction
 * has committed (a rolled-back registration never sends mail).
 *
 * <p>Runs on the module's bounded {@link AuthMailExecutor}. When the pool is saturated the task is
 * dropped and counted; the user can request a resend.
 */
@Component
public class VerificationEmailListener {

    private static final Logger log = LoggerFactory.getLogger(VerificationEmailListener.class);

    private final VerificationEmailSender sender;
    private final AuthMailExecutor executor;
    private final AuthMetrics metrics;

    public VerificationEmailListener(VerificationEmailSender sender, AuthMailExecutor executor, AuthMetrics metrics) {
        this.sender = sender;
        this.executor = executor;
        this.metrics = metrics;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onVerificationEmailRequested(VerificationEmailRequestedEvent event) {
        if (!executor.submit(() -> sender.send(event))) {
            metrics.verificationEmail("rejected");
            log.error("Verification email task rejected: mail executor saturated");
        }
    }
}
