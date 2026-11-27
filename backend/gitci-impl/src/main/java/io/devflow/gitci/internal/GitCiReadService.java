package io.devflow.gitci.internal;

import io.devflow.common.event.WorkspaceMembershipCheckRequestedEvent;
import io.devflow.common.security.AuthenticatedActor;
import io.devflow.gitci.api.TaskGitActivityResponse;
import io.devflow.gitci.api.TaskGitCiRunItem;
import io.devflow.gitci.api.TaskGitCiSummary;
import io.devflow.gitci.api.TaskGitCommitSummary;
import io.devflow.gitci.api.TaskGitPrSummary;
import io.devflow.gitci.api.TaskGitSummary;
import io.devflow.gitci.api.TaskGitSummaryBatchResponse;
import io.devflow.gitci.internal.GitCiSummaryAggregator.CiCandidateRow;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authenticated, workspace-scoped read service serving Git &amp; CI summaries and activity
 * strictly from GitCI-owned tables. Never queries Board/Auth/AI tables directly.
 */
@Service
class GitCiReadService {

    private static final Logger log = LoggerFactory.getLogger(GitCiReadService.class);

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final ApplicationEventPublisher events;
    private final GitCiReadRateLimiter rateLimiter;
    private final MeterRegistry metrics;

    GitCiReadService(
            JdbcTemplate jdbc,
            ApplicationEventPublisher events,
            GitCiReadRateLimiter rateLimiter,
            MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
        this.events = events;
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
    }

    @Transactional(readOnly = true)
    TaskGitSummaryBatchResponse getTaskSummaries(UUID workspaceId, List<UUID> taskIds) {
        long start = System.nanoTime();
        String outcome = "error";
        int status = 500;
        try {
            UUID actorId = requireAuthorizedMember(workspaceId);
            metrics.summary("devflow.gitci.read.batch.size").record(taskIds.size());
            jdbc.execute("SET LOCAL statement_timeout = '3s'");

            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("workspaceId", workspaceId)
                    .addValue("taskIds", taskIds);

            // Query 1: commit counts and latest commit per task (authoritative V16 links)
            Map<UUID, CommitAggregate> commitsByTask = new HashMap<>();
            namedJdbc.query("""
                    WITH ranked AS (
                        SELECT l.task_id, c.sha, c.message, c.committed_at, r.name AS repo_name, r.repository_url,
                               COUNT(*) OVER (PARTITION BY l.task_id) AS commit_count,
                               ROW_NUMBER() OVER (
                                   PARTITION BY l.task_id
                                   ORDER BY c.committed_at DESC, c.created_at DESC, c.id DESC
                               ) AS rn
                        FROM git_commit_task_links l
                        JOIN git_commits c ON c.id = l.commit_id
                        JOIN repositories r ON r.id = c.repository_id
                        WHERE r.workspace_id = :workspaceId AND l.task_id IN (:taskIds)
                    )
                    SELECT task_id, commit_count, sha, message, committed_at, repo_name, repository_url
                    FROM ranked
                    WHERE rn = 1
                    """, params, rs -> {
                UUID taskId = rs.getObject("task_id", UUID.class);
                String sha = rs.getString("sha");
                String repoUrl = rs.getString("repository_url");
                TaskGitCommitSummary latest = new TaskGitCommitSummary(
                        sha,
                        GitHubUrlBuilder.shortSha(sha),
                        GitHubUrlBuilder.messagePreview(rs.getString("message")),
                        GitHubUrlBuilder.commitUrl(repoUrl, sha),
                        instant(rs.getTimestamp("committed_at")),
                        GitHubUrlBuilder.sanitizeInlineText(rs.getString("repo_name"), 255));
                commitsByTask.put(taskId, new CommitAggregate(rs.getInt("commit_count"), latest));
            });

            // Query 2: PR counts and deterministic primary PR (OPEN > MERGED > CLOSED)
            Map<UUID, PrAggregate> prsByTask = new HashMap<>();
            namedJdbc.query("""
                    WITH ranked AS (
                        SELECT pl.task_id, pr.pr_number, pr.title, pr.status, pr.source_branch,
                               COALESCE(pr.provider_updated_at, pr.updated_at) AS effective_updated_at,
                               r.name AS repo_name, r.repository_url,
                               COUNT(*) OVER (PARTITION BY pl.task_id) AS pr_count,
                               COUNT(*) FILTER (WHERE pr.status = 'OPEN') OVER (PARTITION BY pl.task_id) AS open_pr_count,
                               ROW_NUMBER() OVER (
                                   PARTITION BY pl.task_id
                                   ORDER BY CASE pr.status
                                                WHEN 'OPEN' THEN 1
                                                WHEN 'MERGED' THEN 2
                                                WHEN 'CLOSED' THEN 3
                                                ELSE 4
                                            END ASC,
                                            COALESCE(pr.provider_updated_at, pr.updated_at) DESC,
                                            pr.pr_number DESC,
                                            pr.id DESC
                               ) AS rn
                        FROM git_pull_request_task_links pl
                        JOIN pull_requests pr ON pr.id = pl.pull_request_id
                        JOIN repositories r ON r.id = pr.repository_id
                        WHERE r.workspace_id = :workspaceId AND pl.task_id IN (:taskIds)
                    )
                    SELECT task_id, pr_count, open_pr_count, pr_number, title, status,
                           source_branch, effective_updated_at, repo_name, repository_url
                    FROM ranked
                    WHERE rn = 1
                    """, params, rs -> {
                UUID taskId = rs.getObject("task_id", UUID.class);
                int prNumber = rs.getInt("pr_number");
                TaskGitPrSummary primary = new TaskGitPrSummary(
                        prNumber,
                        GitHubUrlBuilder.sanitizeInlineText(rs.getString("title"), 255),
                        rs.getString("status"),
                        GitHubUrlBuilder.pullRequestUrl(rs.getString("repository_url"), prNumber),
                        GitHubUrlBuilder.sanitizeInlineText(rs.getString("source_branch"), 255),
                        instant(rs.getTimestamp("effective_updated_at")),
                        GitHubUrlBuilder.sanitizeInlineText(rs.getString("repo_name"), 255));
                prsByTask.put(taskId, new PrAggregate(rs.getInt("pr_count"), rs.getInt("open_pr_count"), primary));
            });

            // Query 3: latest-attempt CI runs joined strictly by (repository_id, commit_sha)
            Map<UUID, List<CiCandidateRow>> ciByTask = queryCiCandidates(workspaceId, taskIds);

            List<TaskGitSummary> items = new ArrayList<>();
            for (UUID taskId : taskIds) {
                CommitAggregate commitAgg = commitsByTask.get(taskId);
                PrAggregate prAgg = prsByTask.get(taskId);
                TaskGitCiSummary ci = GitCiSummaryAggregator.aggregateCi(ciByTask.get(taskId));
                TaskGitSummary summary = GitCiSummaryAggregator.combine(
                        taskId,
                        commitAgg != null ? commitAgg.count() : 0,
                        commitAgg != null ? commitAgg.latest() : null,
                        prAgg != null ? prAgg.count() : 0,
                        prAgg != null ? prAgg.openCount() : 0,
                        prAgg != null ? prAgg.primary() : null,
                        ci);
                if (summary != null) {
                    items.add(summary);
                }
            }

            outcome = "success";
            status = 200;
            log.debug("GitCI batch read actor={} batchSize={} matched={} durationMs={}",
                    actorId, taskIds.size(), items.size(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            return new TaskGitSummaryBatchResponse(workspaceId, items);
        } catch (GitCiReadException ex) {
            outcome = outcomeFor(ex);
            status = ex.status().value();
            throw ex;
        } catch (DataAccessException ex) {
            outcome = "error";
            status = 503;
            log.warn("GitCI batch read database error exceptionType={}", ex.getClass().getSimpleName());
            throw GitCiReadException.unavailable();
        } finally {
            recordMetrics("task_summaries", outcome, status, start);
        }
    }

    @Transactional(readOnly = true)
    TaskGitActivityResponse getTaskActivity(
            UUID workspaceId, UUID taskId, int commitsLimit, int prsLimit, int ciRunsLimit) {
        long start = System.nanoTime();
        String outcome = "error";
        int status = 500;
        try {
            requireAuthorizedMember(workspaceId);
            jdbc.execute("SET LOCAL statement_timeout = '3s'");

            MapSqlParameterSource baseParams = new MapSqlParameterSource()
                    .addValue("workspaceId", workspaceId)
                    .addValue("taskId", taskId);

            Integer totalCommitsObj = namedJdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM git_commit_task_links l
                    JOIN git_commits c ON c.id = l.commit_id
                    JOIN repositories r ON r.id = c.repository_id
                    WHERE r.workspace_id = :workspaceId AND l.task_id = :taskId
                    """, baseParams, Integer.class);
            int totalCommits = totalCommitsObj != null ? totalCommitsObj : 0;

            List<TaskGitCommitSummary> commits = totalCommits == 0 ? List.of() : namedJdbc.query("""
                    SELECT c.sha, c.message, c.committed_at, r.name AS repo_name, r.repository_url
                    FROM git_commit_task_links l
                    JOIN git_commits c ON c.id = l.commit_id
                    JOIN repositories r ON r.id = c.repository_id
                    WHERE r.workspace_id = :workspaceId AND l.task_id = :taskId
                    ORDER BY c.committed_at DESC, c.created_at DESC, c.id DESC
                    LIMIT :limit
                    """, new MapSqlParameterSource(baseParams.getValues()).addValue("limit", commitsLimit), (rs, n) -> {
                String sha = rs.getString("sha");
                return new TaskGitCommitSummary(
                        sha,
                        GitHubUrlBuilder.shortSha(sha),
                        GitHubUrlBuilder.messagePreview(rs.getString("message")),
                        GitHubUrlBuilder.commitUrl(rs.getString("repository_url"), sha),
                        instant(rs.getTimestamp("committed_at")),
                        GitHubUrlBuilder.sanitizeInlineText(rs.getString("repo_name"), 255));
            });

            int[] prTotals = namedJdbc.query("""
                    SELECT COUNT(*) AS total_prs,
                           COUNT(*) FILTER (WHERE pr.status = 'OPEN') AS open_prs
                    FROM git_pull_request_task_links pl
                    JOIN pull_requests pr ON pr.id = pl.pull_request_id
                    JOIN repositories r ON r.id = pr.repository_id
                    WHERE r.workspace_id = :workspaceId AND pl.task_id = :taskId
                    """, baseParams, rs -> rs.next()
                    ? new int[] {rs.getInt("total_prs"), rs.getInt("open_prs")}
                    : new int[] {0, 0});
            int totalPrs = prTotals != null ? prTotals[0] : 0;
            int openPrs = prTotals != null ? prTotals[1] : 0;

            List<TaskGitPrSummary> pullRequests = totalPrs == 0 ? List.of() : namedJdbc.query("""
                    SELECT pr.pr_number, pr.title, pr.status, pr.source_branch,
                           COALESCE(pr.provider_updated_at, pr.updated_at) AS effective_updated_at,
                           r.name AS repo_name, r.repository_url
                    FROM git_pull_request_task_links pl
                    JOIN pull_requests pr ON pr.id = pl.pull_request_id
                    JOIN repositories r ON r.id = pr.repository_id
                    WHERE r.workspace_id = :workspaceId AND pl.task_id = :taskId
                    ORDER BY CASE pr.status
                                 WHEN 'OPEN' THEN 1
                                 WHEN 'MERGED' THEN 2
                                 WHEN 'CLOSED' THEN 3
                                 ELSE 4
                             END ASC,
                             COALESCE(pr.provider_updated_at, pr.updated_at) DESC,
                             pr.pr_number DESC,
                             pr.id DESC
                    LIMIT :limit
                    """, new MapSqlParameterSource(baseParams.getValues()).addValue("limit", prsLimit), (rs, n) -> {
                int prNumber = rs.getInt("pr_number");
                return new TaskGitPrSummary(
                        prNumber,
                        GitHubUrlBuilder.sanitizeInlineText(rs.getString("title"), 255),
                        rs.getString("status"),
                        GitHubUrlBuilder.pullRequestUrl(rs.getString("repository_url"), prNumber),
                        GitHubUrlBuilder.sanitizeInlineText(rs.getString("source_branch"), 255),
                        instant(rs.getTimestamp("effective_updated_at")),
                        GitHubUrlBuilder.sanitizeInlineText(rs.getString("repo_name"), 255));
            });

            List<CiCandidateRow> ciCandidates = queryCiCandidates(workspaceId, List.of(taskId)).getOrDefault(taskId, List.of());
            TaskGitCiSummary ciSummary = GitCiSummaryAggregator.aggregateCi(ciCandidates);
            int totalCiRuns = GitCiSummaryAggregator.countDistinctRuns(ciCandidates);
            List<TaskGitCiRunItem> ciRuns = GitCiSummaryAggregator.toActivityCiRuns(ciCandidates, ciRunsLimit);

            TaskGitSummary summary = GitCiSummaryAggregator.combine(
                    taskId,
                    totalCommits,
                    commits.isEmpty() ? null : commits.get(0),
                    totalPrs,
                    openPrs,
                    pullRequests.isEmpty() ? null : pullRequests.get(0),
                    ciSummary);

            outcome = "success";
            status = 200;
            return new TaskGitActivityResponse(
                    workspaceId, taskId, summary, totalCommits, commits, totalPrs, pullRequests, totalCiRuns, ciRuns);
        } catch (GitCiReadException ex) {
            outcome = outcomeFor(ex);
            status = ex.status().value();
            throw ex;
        } catch (DataAccessException ex) {
            outcome = "error";
            status = 503;
            log.warn("GitCI task activity read database error exceptionType={}", ex.getClass().getSimpleName());
            throw GitCiReadException.unavailable();
        } finally {
            recordMetrics("task_activity", outcome, status, start);
        }
    }

    private Map<UUID, List<CiCandidateRow>> queryCiCandidates(UUID workspaceId, List<UUID> taskIds) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("workspaceId", workspaceId)
                .addValue("taskIds", taskIds);

        Map<UUID, List<CiCandidateRow>> result = new HashMap<>();
        namedJdbc.query("""
                WITH linked_shas AS (
                    SELECT task_id, repository_id, sha,
                           MAX(sha_time) AS sha_time,
                           MAX(sha_created_at) AS sha_created_at,
                           MAX(repo_name) AS repo_name,
                           MAX(repository_url) AS repository_url
                    FROM (
                        SELECT l.task_id, c.repository_id, c.sha,
                               c.committed_at AS sha_time, c.created_at AS sha_created_at,
                               r.name AS repo_name, r.repository_url
                        FROM git_commit_task_links l
                        JOIN git_commits c ON c.id = l.commit_id
                        JOIN repositories r ON r.id = c.repository_id
                        WHERE r.workspace_id = :workspaceId AND l.task_id IN (:taskIds)
                        UNION ALL
                        SELECT pl.task_id, pr.repository_id, pr.head_sha AS sha,
                               COALESCE(pr.provider_updated_at, pr.updated_at) AS sha_time,
                               pr.created_at AS sha_created_at,
                               r.name AS repo_name, r.repository_url
                        FROM git_pull_request_task_links pl
                        JOIN pull_requests pr ON pr.id = pl.pull_request_id
                        JOIN repositories r ON r.id = pr.repository_id
                        WHERE r.workspace_id = :workspaceId AND pl.task_id IN (:taskIds)
                          AND pr.head_sha IS NOT NULL
                    ) raw_shas
                    GROUP BY task_id, repository_id, sha
                ),
                ranked_shas AS (
                    SELECT task_id, repository_id, sha, sha_time, sha_created_at, repo_name, repository_url,
                           (ROW_NUMBER() OVER (
                               PARTITION BY task_id, repository_id
                               ORDER BY sha_time DESC, sha_created_at DESC, sha DESC
                           ) > 1) AS stale_for_latest_commit
                    FROM linked_shas
                ),
                matched_pipelines AS (
                    SELECT rs.task_id, rs.repository_id, rs.sha, rs.sha_time, rs.sha_created_at,
                           rs.stale_for_latest_commit, rs.repo_name, rs.repository_url,
                           p.pipeline_id, p.run_attempt, p.status, p.provider_conclusion,
                           p.started_at, p.finished_at, p.provider_updated_at, p.created_at AS pipeline_created_at,
                           ROW_NUMBER() OVER (
                               PARTITION BY rs.task_id, p.repository_id, p.pipeline_id
                               ORDER BY p.run_attempt DESC
                           ) AS attempt_rn
                    FROM ranked_shas rs
                    JOIN ci_pipelines p
                      ON p.repository_id = rs.repository_id AND p.commit_sha = rs.sha
                )
                SELECT task_id, repository_id, sha, sha_time, sha_created_at,
                       stale_for_latest_commit, repo_name, repository_url,
                       pipeline_id, run_attempt, status, provider_conclusion,
                       started_at, finished_at, provider_updated_at, pipeline_created_at
                FROM matched_pipelines
                WHERE attempt_rn = 1
                """, params, rs -> {
            UUID taskId = rs.getObject("task_id", UUID.class);
            CiCandidateRow row = new CiCandidateRow(
                    taskId,
                    rs.getObject("repository_id", UUID.class),
                    rs.getString("sha"),
                    instant(rs.getTimestamp("sha_time")),
                    instant(rs.getTimestamp("sha_created_at")),
                    rs.getBoolean("stale_for_latest_commit"),
                    rs.getString("repo_name"),
                    rs.getString("repository_url"),
                    rs.getString("pipeline_id"),
                    rs.getInt("run_attempt"),
                    rs.getString("status"),
                    rs.getString("provider_conclusion"),
                    instant(rs.getTimestamp("started_at")),
                    instant(rs.getTimestamp("finished_at")),
                    instant(rs.getTimestamp("provider_updated_at")),
                    instant(rs.getTimestamp("pipeline_created_at")));
            result.computeIfAbsent(taskId, ignored -> new ArrayList<>()).add(row);
        });
        return result;
    }

    private UUID requireAuthorizedMember(UUID workspaceId) {
        UUID userId = requireUserId();
        rateLimiter.check(userId);
        UUID correlationId = correlationId();
        WorkspaceMembershipCheckRequestedEvent event =
                new WorkspaceMembershipCheckRequestedEvent(correlationId, userId, workspaceId);
        try {
            events.publishEvent(event);
        } catch (RuntimeException ex) {
            log.error("Workspace membership check failed in GitCI read; correlationId={}", correlationId);
            throw GitCiReadException.unavailable();
        }
        if (event.responseCount() != 1) {
            log.error("Workspace membership check returned {} decisions in GitCI read; correlationId={}",
                    event.responseCount(), correlationId);
            throw GitCiReadException.unavailable();
        }
        return switch (event.decision()) {
            case MEMBER -> userId;
            case NOT_MEMBER -> throw GitCiReadException.workspaceNotFound();
            case PENDING, AMBIGUOUS -> {
                log.error("Workspace membership check returned invalid decision in GitCI read; correlationId={}",
                        correlationId);
                throw GitCiReadException.unavailable();
            }
        };
    }

    private static UUID requireUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AuthenticatedActor actor)
                || actor.userId() == null) {
            throw GitCiReadException.authenticationRequired();
        }
        UUID userId = actor.userId();
        MDC.put("userId", userId.toString());
        return userId;
    }

    private static UUID correlationId() {
        String raw = MDC.get("correlationId");
        if (raw != null && !raw.isBlank()) {
            try {
                return UUID.fromString(raw);
            } catch (IllegalArgumentException ignored) {
                // Fall through to fresh UUID
            }
        }
        return UUID.randomUUID();
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static String outcomeFor(GitCiReadException ex) {
        return switch (ex.code()) {
            case "AUTHENTICATION_REQUIRED", "WORKSPACE_NOT_FOUND" -> "denied";
            case "VALIDATION_FAILED" -> "invalid";
            case "RATE_LIMIT_EXCEEDED" -> "rate_limited";
            default -> "error";
        };
    }

    private void recordMetrics(String endpoint, String outcome, int status, long startNanos) {
        try {
            metrics.counter("devflow.gitci.read.requests",
                    "endpoint", endpoint,
                    "outcome", outcome,
                    "status", Integer.toString(status)).increment();
            metrics.timer("devflow.gitci.read.duration", "endpoint", endpoint)
                    .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
        } catch (RuntimeException ignored) {
            // Observability failure must never break read responses.
        }
    }

    private record CommitAggregate(int count, TaskGitCommitSummary latest) {
    }

    private record PrAggregate(int count, int openCount, TaskGitPrSummary primary) {
    }
}
