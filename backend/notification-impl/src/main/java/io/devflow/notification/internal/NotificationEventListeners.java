package io.devflow.notification.internal;

import io.devflow.common.event.CiFailureSummarizedEvent;
import io.devflow.common.event.GitPrMergedEvent;
import io.devflow.common.event.GitPrOpenedEvent;
import io.devflow.common.event.RiskDeadlineFlaggedEvent;
import io.devflow.common.event.TaskCreatedEvent;
import io.devflow.common.event.TaskStatusChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    @EventListener
    public void on(TaskCreatedEvent event) {
        log.info("[{}] task={}", event.eventType(), event.taskId());
        // TODO(PRODUCT_SPEC 5.1): notify on new assignment.
    }

    @EventListener
    public void on(TaskStatusChangedEvent event) {
        log.info("[{}] task={} {} -> {}", event.eventType(), event.taskId(), event.oldStatus(), event.newStatus());
        // TODO(PRODUCT_SPEC 5.1/5.2a): surface automatic status changes to the assignee.
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
}
