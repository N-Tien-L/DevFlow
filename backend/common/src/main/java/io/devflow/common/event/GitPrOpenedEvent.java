package io.devflow.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code git.pr_opened} — published by Git &amp; CI when a PR referencing a task opens.
 * Consumed by Board (move task to "In Review") and Notification
 * (PRODUCT_SPEC.md Section 5.2a, ARCHITECTURE.md Section 4).
 *
 * @param taskId task referenced by the PR
 * @param prUrl  URL of the opened pull request
 * @param repo   repository the PR targets
 */
public record GitPrOpenedEvent(UUID taskId, String prUrl, String repo, Instant occurredAt)
        implements DevFlowEvent {

    public GitPrOpenedEvent(UUID taskId, String prUrl, String repo) {
        this(taskId, prUrl, repo, Instant.now());
    }

    @Override
    public String eventType() {
        return EventTypes.GIT_PR_OPENED;
    }
}
