package io.devflow.gitci.api;

import java.time.Instant;

/** Single latest-attempt CI pipeline run linked to a task. */
public record TaskGitCiRunItem(
        String status,
        String providerConclusion,
        String runId,
        int runAttempt,
        String commitSha,
        String repositoryName,
        String url,
        Instant startedAt,
        Instant finishedAt) {
}
