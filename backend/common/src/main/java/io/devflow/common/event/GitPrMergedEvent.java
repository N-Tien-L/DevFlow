package io.devflow.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code git.pr_merged} — published by Git &amp; CI when a PR referencing a task merges.
 * Consumed by Board (move task to "Done") and Notification
 * (PRODUCT_SPEC.md Section 5.2a, ARCHITECTURE.md Section 4).
 *
 * @param taskId task referenced by the PR
 * @param prUrl  URL of the merged pull request
 * @param repo   repository the PR was merged into
 * @param actorId DevFlow user who merged the PR; null when the producer cannot map the Git actor
 */
public record GitPrMergedEvent(UUID taskId, String prUrl, String repo, UUID actorId, Instant occurredAt)
        implements DevFlowEvent {

    public GitPrMergedEvent(UUID taskId, String prUrl, String repo) {
        this(taskId, prUrl, repo, null, Instant.now());
    }

    /** Compatibility constructor for existing Git producers with no DevFlow actor mapping. */
    public GitPrMergedEvent(UUID taskId, String prUrl, String repo, Instant occurredAt) {
        this(taskId, prUrl, repo, null, occurredAt);
    }

    @Override
    public String eventType() {
        return EventTypes.GIT_PR_MERGED;
    }
}
