package io.devflow.gitci.api;

import java.time.Instant;

/** Aggregated CI status for the newest CI-evaluated commit(s) linked to a task. */
public record TaskGitCiSummary(
        String status,
        String providerConclusion,
        int failedRunCount,
        String runId,
        int runAttempt,
        String commitSha,
        String repositoryName,
        String url,
        Instant finishedAt,
        boolean staleForLatestCommit) {
}
