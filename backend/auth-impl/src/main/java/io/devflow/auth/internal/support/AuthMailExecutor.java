package io.devflow.auth.internal.support;

import java.util.Map;
import org.slf4j.MDC;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.core.task.TaskDecorator;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * Bounded pool that delivers the auth module's transactional email (verification, workspace invitations)
 * off the request thread.
 *
 * <p>Private to the module rather than an application-wide {@code @Async} executor bean, so a slow mail
 * provider cannot exhaust request threads and the Spring Boot default task executor stays intact.
 * The MDC (correlation id) of the submitting thread is carried over to the mail thread.
 */
@Component
public class AuthMailExecutor implements DisposableBean {

    private final ThreadPoolTaskExecutor executor;

    public AuthMailExecutor() {
        this.executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("auth-mail-");
        executor.setTaskDecorator(mdcPropagatingDecorator());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
    }

    /**
     * Queues a mail task.
     *
     * @return {@code false} when the pool is saturated (or shutting down) and the task was dropped
     */
    public boolean submit(Runnable task) {
        try {
            executor.execute(task);
            return true;
        } catch (TaskRejectedException e) {
            return false;
        }
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
