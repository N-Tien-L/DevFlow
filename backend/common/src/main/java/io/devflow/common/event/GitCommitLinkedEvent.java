package io.devflow.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code git.commit_linked} — published by Git &amp; CI when a branch/commit following the
 * task-ID naming convention is linked to a task (PRODUCT_SPEC.md Section 5.2a).
 * Consumed by Board, which moves the task to "In Progress" on first commit
 * (ARCHITECTURE.md Section 4).
 *
 * @param taskId    task referenced by the commit/branch naming convention
 * @param commitSha linked commit SHA
 * @param repo      repository the commit was pushed to
 * @param author    commit author (used for attribution and notifications)
 */
public record GitCommitLinkedEvent(UUID taskId, String commitSha, String repo, String author, Instant occurredAt)
        implements DevFlowEvent {

    public GitCommitLinkedEvent(UUID taskId, String commitSha, String repo, String author) {
        this(taskId, commitSha, repo, author, Instant.now());
    }

    @Override
    public String eventType() {
        return EventTypes.GIT_COMMIT_LINKED;
    }
}
