package io.devflow.ai.internal;

import io.devflow.common.event.CiFailureDetectedEvent;
import io.devflow.common.event.TaskCreatedEvent;
import io.devflow.common.event.TaskStatusChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    @EventListener
    public void on(CiFailureDetectedEvent event) {
        log.info("[{}] pipeline={} repo={} sha={}",
                event.eventType(), event.pipelineId(), event.repo(), event.commitSha());
        // TODO(PRODUCT_SPEC 5.2a): summarize via AiService, then publish
        //   CiFailureSummarizedEvent(pipelineId, summary, relatedTaskId).
    }

    @EventListener
    public void on(TaskCreatedEvent event) {
        log.info("[{}] task={} project={}", event.eventType(), event.taskId(), event.projectId());
        // TODO(PRODUCT_SPEC 5.2b): feed into deadline-risk tracking.
    }

    @EventListener
    public void on(TaskStatusChangedEvent event) {
        log.info("[{}] task={} {} -> {} ({})",
                event.eventType(), event.taskId(), event.oldStatus(), event.newStatus(), event.cause());
        // TODO(PRODUCT_SPEC 5.2b): update completion pace; publish RiskDeadlineFlaggedEvent
        //   when pace vs. plan indicates risk.
    }
}
