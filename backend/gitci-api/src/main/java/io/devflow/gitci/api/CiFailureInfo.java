package io.devflow.gitci.api;

import java.time.Instant;

/**
 * Read-only view of a recorded CI pipeline failure exposed to other modules. Raw logs stay
 * inside the Git &amp; CI module — consumers get a reference, not the payload
 * (ARCHITECTURE.md Section 10).
 */
public record CiFailureInfo(
        String pipelineId, String repo, String commitSha, String rawLogReference, Instant failedAt) {
}
