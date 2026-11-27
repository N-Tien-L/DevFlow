package io.devflow.gitci.api;

import java.time.Instant;

/** Public, privacy-safe summary of a commit linked to a task (never exposes author email). */
public record TaskGitCommitSummary(
        String sha,
        String shortSha,
        String messagePreview,
        String url,
        Instant committedAt,
        String repositoryName) {
}
