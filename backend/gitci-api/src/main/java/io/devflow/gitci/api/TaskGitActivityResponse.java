package io.devflow.gitci.api;

import java.util.List;
import java.util.UUID;

/** Bounded read-only Git &amp; CI activity breakdown for a single task inside TaskDetailModal. */
public record TaskGitActivityResponse(
        UUID workspaceId,
        UUID taskId,
        TaskGitSummary summary,
        int totalCommits,
        List<TaskGitCommitSummary> commits,
        int totalPullRequests,
        List<TaskGitPrSummary> pullRequests,
        int totalCiRuns,
        List<TaskGitCiRunItem> ciRuns) {

    public TaskGitActivityResponse {
        commits = commits == null ? List.of() : List.copyOf(commits);
        pullRequests = pullRequests == null ? List.of() : List.copyOf(pullRequests);
        ciRuns = ciRuns == null ? List.of() : List.copyOf(ciRuns);
    }
}
