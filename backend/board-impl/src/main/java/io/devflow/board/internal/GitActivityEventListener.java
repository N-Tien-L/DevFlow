package io.devflow.board.internal;

import io.devflow.common.event.GitCommitLinkedEvent;
import io.devflow.common.event.GitPrMergedEvent;
import io.devflow.common.event.GitPrOpenedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Board's subscriptions to Git &amp; CI events (ARCHITECTURE.md Section 4). This listener
 * is the automatic status-update pipeline of PRODUCT_SPEC.md Section 5.2a:
 * first commit → "In Progress", PR opened → "In Review", PR merged → "Done".
 *
 * <p>The status updates themselves are Board-internal state changes; after applying one,
 * publish a {@code TaskStatusChangedEvent} with cause GIT so Notification and the AI
 * service see it (Section 4 contract).
 */
@Component
public class GitActivityEventListener {

    private static final Logger log = LoggerFactory.getLogger(GitActivityEventListener.class);

    @EventListener
    public void on(GitCommitLinkedEvent event) {
        log.info("[{}] task={} commit={} repo={}",
                event.eventType(), event.taskId(), event.commitSha(), event.repo());
        // TODO(PRODUCT_SPEC 5.2a): move the task to "In Progress" on first linked commit,
        //   then publish TaskStatusChangedEvent(cause = GIT).
    }

    @EventListener
    public void on(GitPrOpenedEvent event) {
        log.info("[{}] task={} pr={}", event.eventType(), event.taskId(), event.prUrl());
        // TODO(PRODUCT_SPEC 5.2a): move the task to "In Review",
        //   then publish TaskStatusChangedEvent(cause = GIT).
    }

    @EventListener
    public void on(GitPrMergedEvent event) {
        log.info("[{}] task={} pr={}", event.eventType(), event.taskId(), event.prUrl());
        // TODO(PRODUCT_SPEC 5.2a): move the task to "Done",
        //   then publish TaskStatusChangedEvent(cause = GIT).
    }
}
