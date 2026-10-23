package io.devflow.ai.internal;

import io.devflow.common.event.CiFailureDetectedEvent;
import io.devflow.common.event.TaskCreatedEvent;
import io.devflow.common.event.TaskStatusChangedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * AI service's subscriptions on the event bus (ARCHITECTURE.md Section 4).
 *
 * <p>TODO(PRODUCT_SPEC Section 5.2):
 * <ul>
 *   <li>On {@code ci.failure_detected}: summarize the failure and publish
 *       {@code CiFailureSummarizedEvent} for Board and Notification</li>
 *   <li>On {@code task.created} / {@code task.status_changed}: track completion pace vs.
 *       plan and publish {@code RiskDeadlineFlaggedEvent} when a deadline is at risk
 *       (proactive deadline risk alerts)</li>
 * </ul>
 */
@Component
public class AiEventListeners {

    private static final Logger log = LoggerFactory.getLogger(AiEventListeners.class);
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    public AiEventListeners(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.meterRegistryProvider = meterRegistryProvider;
    }

    @EventListener
    public void on(CiFailureDetectedEvent event) {
        log.info("[{}] pipeline={} repo={} sha={}",
                event.eventType(), event.pipelineId(), event.repo(), event.commitSha());
        // TODO(PRODUCT_SPEC 5.2a): summarize via AiService, then publish
        //   CiFailureSummarizedEvent(pipelineId, summary, relatedTaskId).
    }

    @EventListener
    public void on(TaskCreatedEvent event) {
        consume(event.eventType(), event.eventId(), event.taskId(), () -> {
            log.info("[{}] eventId={} task={} board={} workspace={} column={} actor={}",
                    event.eventType(), event.eventId(), event.taskId(), event.boardId(), event.projectId(),
                    event.columnId(), event.actorId());
            // TODO(PRODUCT_SPEC 5.2b): feed into deadline-risk tracking.
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
            // TODO(PRODUCT_SPEC 5.2b): update completion pace; publish RiskDeadlineFlaggedEvent
            //   when pace vs. plan indicates risk.
        });
    }

    private void consume(String eventType, java.util.UUID eventId, java.util.UUID taskId, Runnable action) {
        String outcome = "success";
        try {
            action.run();
        } catch (RuntimeException exception) {
            outcome = "failure";
            log.error("AI task event handler failed type={} eventId={} taskId={} exceptionType={}",
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
                        "eventType", eventType, "consumer", "ai", "outcome", outcome).increment();
            }
        } catch (RuntimeException exception) {
            log.warn("AI task event metric failed type={} exceptionType={}",
                    eventType, exception.getClass().getSimpleName());
        }
    }
}
