package io.devflow.notification.internal;

import io.devflow.common.event.CiFailureSummarizedEvent;
import io.devflow.common.event.GitPrMergedEvent;
import io.devflow.common.event.GitPrOpenedEvent;
import io.devflow.common.event.RiskDeadlineFlaggedEvent;
import io.devflow.common.event.TaskCreatedEvent;
import io.devflow.common.event.TaskStatusChangedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Notification's subscriptions on the event bus (ARCHITECTURE.md Section 4). Each handler
 * turns an event into a delivered notification via {@link NotificationService}.
 *
 * <p>TODO(PRODUCT_SPEC Section 5.1 — Notifications): map each event to its recipients
 * (assignee, watchers, workspace members) and message text, then deliver.
 */
@Component
public class NotificationEventListeners {

    private static final Logger log = LoggerFactory.getLogger(NotificationEventListeners.class);
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    public NotificationEventListeners(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.meterRegistryProvider = meterRegistryProvider;
    }

    @EventListener
    public void on(TaskCreatedEvent event) {
        consume(event.eventType(), event.eventId(), event.taskId(), () -> {
            log.info("[{}] eventId={} task={} board={} workspace={} column={} actor={}",
                    event.eventType(), event.eventId(), event.taskId(), event.boardId(), event.projectId(),
                    event.columnId(), event.actorId());
            // TODO(PRODUCT_SPEC 5.1): notify on new assignment.
        });
    }

    @EventListener
    public void on(TaskStatusChangedEvent event) {
        consume(event.eventType(), event.eventId(), event.taskId(), () -> {
            log.info("[{}] eventId={} task={} board={} workspace={} {} -> {} category={} -> {} cause={} "
                            + "correlationId={} actorId={}",
                    event.eventType(), event.eventId(), event.taskId(), event.boardId(), event.workspaceId(),
                    event.sourceColumnId(), event.destinationColumnId(), event.oldStatusCategory(),
                    event.newStatusCategory(), event.cause(), event.correlationId(), event.actorId());
            // TODO(PRODUCT_SPEC 5.1/5.2a): surface automatic status changes to the assignee.
        });
    }

    @EventListener
    public void on(GitPrOpenedEvent event) {
        log.info("[{}] task={} pr={}", event.eventType(), event.taskId(), event.prUrl());
        // TODO(PRODUCT_SPEC 5.2a): notify reviewers.
    }

    @EventListener
    public void on(GitPrMergedEvent event) {
        log.info("[{}] task={} pr={}", event.eventType(), event.taskId(), event.prUrl());
        // TODO(PRODUCT_SPEC 5.2a): notify the task owner that their work merged.
    }

    @EventListener
    public void on(CiFailureSummarizedEvent event) {
        log.info("[{}] pipeline={} relatedTask={}", event.eventType(), event.pipelineId(), event.relatedTaskId());
        // TODO(PRODUCT_SPEC 5.2a): deliver the AI failure summary to whoever can act on it.
    }

    @EventListener
    public void on(RiskDeadlineFlaggedEvent event) {
        log.info("[{}] task={} sprint={} severity={}",
                event.eventType(), event.taskId(), event.sprintId(), event.severity());
        // TODO(PRODUCT_SPEC 5.2b): deliver proactive deadline risk alerts.
    }

    private void consume(String eventType, java.util.UUID eventId, java.util.UUID taskId, Runnable action) {
        String outcome = "success";
        try {
            action.run();
        } catch (RuntimeException exception) {
            outcome = "failure";
            log.error("Notification task event handler failed type={} eventId={} taskId={} exceptionType={}",
                    eventType, eventId, taskId, exception.getClass().getSimpleName());
        } finally {
            recordConsumeMetric(eventType, outcome);
        }
    }

    private void recordConsumeMetric(String eventType, String outcome) {
        try {
            MeterRegistry registry = meterRegistryProvider.getIfAvailable();
            if (registry != null) {
                registry.counter("devflow.task.events.consume",
                        "eventType", eventType, "consumer", "notification", "outcome", outcome).increment();
            }
        } catch (RuntimeException exception) {
            log.warn("Notification task event metric failed type={} exceptionType={}",
                    eventType, exception.getClass().getSimpleName());
        }
    }
}
