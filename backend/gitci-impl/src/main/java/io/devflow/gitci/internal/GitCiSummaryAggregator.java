package io.devflow.gitci.internal;

import io.devflow.gitci.api.TaskGitCiRunItem;
import io.devflow.gitci.api.TaskGitCiSummary;
import io.devflow.gitci.api.TaskGitCommitSummary;
import io.devflow.gitci.api.TaskGitPrSummary;
import io.devflow.gitci.api.TaskGitSummary;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Deterministic aggregation rules for Kanban task Git &amp; CI summaries. */
final class GitCiSummaryAggregator {

    record CiCandidateRow(
            UUID taskId,
            UUID repositoryId,
            String sha,
            Instant shaTime,
            Instant shaCreatedAt,
            boolean staleForLatestCommit,
            String repoName,
            String repoUrl,
            String pipelineId,
            int runAttempt,
            String status,
            String providerConclusion,
            Instant startedAt,
            Instant finishedAt,
            Instant providerUpdatedAt,
            Instant pipelineCreatedAt) {

        Instant effectiveCiTime() {
            if (providerUpdatedAt != null) return providerUpdatedAt;
            if (finishedAt != null) return finishedAt;
            return pipelineCreatedAt != null ? pipelineCreatedAt : Instant.EPOCH;
        }
    }

    private static final Comparator<CiCandidateRow> SHA_RECENCY_DESC = Comparator
            .comparing((CiCandidateRow r) -> r.shaTime() != null ? r.shaTime() : Instant.EPOCH, Comparator.reverseOrder())
            .thenComparing(r -> r.shaCreatedAt() != null ? r.shaCreatedAt() : Instant.EPOCH, Comparator.reverseOrder())
            .thenComparing(CiCandidateRow::sha, Comparator.reverseOrder());

    private static final Comparator<CiCandidateRow> CI_WINNER_COMPARATOR = (left, right) -> {
        int severity = Integer.compare(ciStatusSeverity(left.status()), ciStatusSeverity(right.status()));
        if (severity != 0) return severity;
        int time = right.effectiveCiTime().compareTo(left.effectiveCiTime());
        if (time != 0) return time;
        int runId = right.pipelineId().compareTo(left.pipelineId());
        if (runId != 0) return runId;
        return Integer.compare(right.runAttempt(), left.runAttempt());
    };

    private GitCiSummaryAggregator() {
    }

    /** Explicit priority order for selecting the primary PR badge (OPEN > MERGED > CLOSED). */
    static int prStatusPriority(String status) {
        if (status == null) return 4;
        return switch (status) {
            case "OPEN" -> 1;
            case "MERGED" -> 2;
            case "CLOSED" -> 3;
            default -> 4;
        };
    }

    /**
     * Explicit severity order for aggregating multiple workflows / repositories on a task's
     * evaluated commit(s). Never relies on enum ordinal.
     */
    static int ciStatusSeverity(String status) {
        if (status == null) return 8;
        return switch (status) {
            case "FAILED" -> 1;
            case "TIMED_OUT" -> 2;
            case "ACTION_REQUIRED" -> 3;
            case "RUNNING" -> 4;
            case "PENDING" -> 5;
            case "CANCELLED" -> 6;
            case "SUCCESS" -> 7;
            default -> 8;
        };
    }

    /**
     * Aggregates CI candidates for a single task:
     * 1. Collapse duplicates per (repositoryId, pipelineId) keeping only the highest runAttempt.
     * 2. Per repository, keep only pipelines on the newest linked commit SHA that has CI runs
     *    (so an older commit rerun cannot resurrect a stale failure over a newer green commit).
     * 3. Across those evaluated workflows and repositories, select the highest-severity status
     *    and count distinct failed workflows.
     */
    static TaskGitCiSummary aggregateCi(List<CiCandidateRow> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }

        // Step 1: ensure only MAX(runAttempt) survives per (repositoryId, pipelineId)
        Map<String, CiCandidateRow> latestByRun = new HashMap<>();
        for (CiCandidateRow row : candidates) {
            String key = row.repositoryId() + ":" + row.pipelineId();
            CiCandidateRow existing = latestByRun.get(key);
            if (existing == null || row.runAttempt() > existing.runAttempt()) {
                latestByRun.put(key, row);
            }
        }

        // Step 2: group by repository and select only the newest CI-evaluated SHA per repo
        Map<UUID, List<CiCandidateRow>> byRepo = new HashMap<>();
        for (CiCandidateRow row : latestByRun.values()) {
            byRepo.computeIfAbsent(row.repositoryId(), ignored -> new ArrayList<>()).add(row);
        }

        List<CiCandidateRow> evaluated = new ArrayList<>();
        for (List<CiCandidateRow> repoRows : byRepo.values()) {
            repoRows.sort(SHA_RECENCY_DESC);
            String chosenSha = repoRows.get(0).sha();
            for (CiCandidateRow row : repoRows) {
                if (Objects.equals(row.sha(), chosenSha)) {
                    evaluated.add(row);
                }
            }
        }

        if (evaluated.isEmpty()) {
            return null;
        }

        // Step 3: count distinct failed workflows and select winning CI run
        Set<String> failedRuns = new HashSet<>();
        for (CiCandidateRow row : evaluated) {
            if ("FAILED".equals(row.status())) {
                failedRuns.add(row.repositoryId() + ":" + row.pipelineId());
            }
        }

        evaluated.sort(CI_WINNER_COMPARATOR);
        CiCandidateRow winner = evaluated.get(0);
        return new TaskGitCiSummary(
                winner.status(),
                winner.providerConclusion(),
                failedRuns.size(),
                winner.pipelineId(),
                winner.runAttempt(),
                winner.sha(),
                GitHubUrlBuilder.sanitizeInlineText(winner.repoName(), 255),
                GitHubUrlBuilder.ciAttemptUrl(winner.repoUrl(), winner.pipelineId(), winner.runAttempt()),
                winner.finishedAt() != null ? winner.finishedAt() : winner.providerUpdatedAt(),
                winner.staleForLatestCommit());
    }

    /** Builds the sorted, bounded latest-attempt CI run list for TaskDetailModal. */
    static List<TaskGitCiRunItem> toActivityCiRuns(List<CiCandidateRow> candidates, int limit) {
        if (candidates == null || candidates.isEmpty() || limit <= 0) {
            return List.of();
        }
        Map<String, CiCandidateRow> latestByRun = new HashMap<>();
        for (CiCandidateRow row : candidates) {
            String key = row.repositoryId() + ":" + row.pipelineId();
            CiCandidateRow existing = latestByRun.get(key);
            if (existing == null || row.runAttempt() > existing.runAttempt()) {
                latestByRun.put(key, row);
            }
        }
        List<CiCandidateRow> sorted = new ArrayList<>(latestByRun.values());
        sorted.sort(SHA_RECENCY_DESC.thenComparing(CI_WINNER_COMPARATOR));
        return sorted.stream()
                .limit(limit)
                .map(row -> new TaskGitCiRunItem(
                        row.status(),
                        row.providerConclusion(),
                        row.pipelineId(),
                        row.runAttempt(),
                        row.sha(),
                        GitHubUrlBuilder.sanitizeInlineText(row.repoName(), 255),
                        GitHubUrlBuilder.ciAttemptUrl(row.repoUrl(), row.pipelineId(), row.runAttempt()),
                        row.startedAt(),
                        row.finishedAt() != null ? row.finishedAt() : row.providerUpdatedAt()))
                .toList();
    }

    static int countDistinctRuns(List<CiCandidateRow> candidates) {
        if (candidates == null || candidates.isEmpty()) return 0;
        Set<String> runs = new HashSet<>();
        for (CiCandidateRow row : candidates) {
            runs.add(row.repositoryId() + ":" + row.pipelineId());
        }
        return runs.size();
    }

    static TaskGitSummary combine(
            UUID taskId,
            int commitCount,
            TaskGitCommitSummary latestCommit,
            int prCount,
            int openPrCount,
            TaskGitPrSummary primaryPr,
            TaskGitCiSummary ci) {
        if (commitCount <= 0 && prCount <= 0 && ci == null) {
            return null;
        }
        return new TaskGitSummary(taskId, commitCount, latestCommit, prCount, openPrCount, primaryPr, ci);
    }
}
