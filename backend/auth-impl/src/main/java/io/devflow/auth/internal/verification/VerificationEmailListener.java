package io.devflow.auth.internal.verification;

import io.devflow.auth.internal.support.AuthMetrics;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Dispatches verification emails off the request thread, and only after the issuing transaction
 * has committed (a rolled-back registration never sends mail).
 *
 * <p>Uses a private bounded pool rather than an application-wide {@code @Async} executor bean, so a
 * slow mail provider cannot exhaust request threads and the Spring Boot default task executor stays intact.
 * When the pool is saturated the task is dropped and counted; the user can request a resend.
 */
@Component
public class VerificationEmailListener implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(VerificationEmailListener.class);

    private final VerificationEmailSender sender;
    private final ThreadPoolTaskExecutor executor;

    public VerificationEmailListener(VerificationEmailSender sender, AuthMetrics metrics) {
        this.sender = sender;
        this.executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("auth-mail-");
        executor.setTaskDecorator(mdcPropagatingDecorator());
        executor.setRejectedExecutionHandler((task, pool) -> {
            metrics.verificationEmail("rejected");
            log.error("Verification email task rejected: mail executor saturated");
        });
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onVerificationEmailRequested(VerificationEmailRequestedEvent event) {
        executor.execute(() -> sender.send(event));
    }

    @Override
    public void destroy() {
        executor.shutdown();
    }

    private static TaskDecorator mdcPropagatingDecorator() {
        return runnable -> {
            Map<String, String> context = MDC.getCopyOfContextMap();
            return () -> {
                if (context != null) {
                    MDC.setContextMap(context);
                }
                try {
                    runnable.run();
                } finally {
                    MDC.clear();
                }
            };
        };
    }
}
