package io.devflow.gitci.api;

import java.time.Instant;

/** Public summary of a pull request linked to a task. */
public record TaskGitPrSummary(
        int prNumber,
        String title,
        String status,
        String url,
        String sourceBranch,
        Instant updatedAt,
        String repositoryName) {
}
