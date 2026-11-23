package io.devflow.ai.internal.ci;

import java.util.Map;
import java.util.concurrent.Executor;
import org.slf4j.MDC;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.core.task.TaskDecorator;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * Dedicated bounded thread pool for asynchronous CI failure log summarization (T-031).
 *
 * <p>Ensures that slow LLM responses or log fetches never block the incoming webhook thread.
 * Preserves the MDC correlation id across threads.
 */
@Component
public class AiCiExecutor implements DisposableBean {

    private final Executor executor;
    private final ThreadPoolTaskExecutor taskExecutor;

    public AiCiExecutor() {
        this.taskExecutor = new ThreadPoolTaskExecutor();
        taskExecutor.setCorePoolSize(2);
        taskExecutor.setMaxPoolSize(4);
        taskExecutor.setQueueCapacity(200);
        taskExecutor.setThreadNamePrefix("ai-ci-");
        taskExecutor.setTaskDecorator(mdcPropagatingDecorator());
        taskExecutor.setWaitForTasksToCompleteOnShutdown(true);
        taskExecutor.setAwaitTerminationSeconds(30);
        taskExecutor.initialize();
        this.executor = taskExecutor;
    }

    /** Constructor for testing with direct or custom executor. */
    public AiCiExecutor(Executor executor) {
        this.executor = executor;
        this.taskExecutor = null;
    }

    /**
     * Submits a CI summarization task for execution.
     *
     * @return {@code true} if queued, {@code false} if pool is saturated
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
        if (taskExecutor != null) {
            taskExecutor.shutdown();
        }
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
