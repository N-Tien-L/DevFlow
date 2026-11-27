package io.devflow.gitci.internal;

import com.fasterxml.jackson.databind.JsonNode;
import io.devflow.common.event.TaskGitActivityChangedEvent.ActivityKind;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Persists a completed attempt under the repository lock held by webhook receipt ingestion. */
@Component
final class CiWorkflowIngestion {
    private static final Set<String> CONCLUSIONS = Set.of(
            "cancelled", "skipped", "timed_out", "action_required", "neutral", "stale");
    private final JdbcTemplate jdbc;
    private final GitHubActionsProperties properties;
    private final MeterRegistry metrics;
    private final ApplicationEventPublisher events;

    CiWorkflowIngestion(JdbcTemplate jdbc, GitHubActionsProperties properties, MeterRegistry metrics) {
        this(jdbc, properties, metrics, null);
    }

    @Autowired
    CiWorkflowIngestion(JdbcTemplate jdbc, GitHubActionsProperties properties, MeterRegistry metrics,
                        ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.metrics = metrics;
        this.events = events;
    }

    record Attempt(String runId, int number, String sha, String branch, String status, String conclusion,
                   Instant started, Instant updated) { }

    private record Stored(UUID id, String sha, String branch, String status, String conclusion,
                          Instant started, Instant updated) { }

    Attempt prepare(JsonNode root, String repositoryId) {
        String action = text(root.path("action"));
        if (!action.matches("[a-z0-9_]{1,64}")) throw invalid();
        if (!action.equals("completed")) return null;
        JsonNode run = root.path("workflow_run");
        if (run.has("repository")) {
            JsonNode repoId = run.path("repository").path("id");
            if (!repoId.isIntegralNumber() || repoId.bigIntegerValue().signum() <= 0)
                throw invalid();
            if (!repoId.bigIntegerValue().toString().equals(repositoryId))
                throw new WebhookFailure(401, "WEBHOOK_UNAUTHORIZED");
        }
        JsonNode id = run.path("id");
        JsonNode attempt = run.path("run_attempt");
        if (!id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() <= 0
                || !attempt.isIntegralNumber() || !attempt.canConvertToInt() || attempt.intValue() <= 0)
            throw invalid();
        String sha = text(run.path("head_sha"));
        String conclusion = text(run.path("conclusion"));
        if (!validSha(sha) || !"completed".equals(text(run.path("status")))
                || !conclusion.matches("[a-z][a-z0-9_]{0,49}")) throw invalid();
        JsonNode branchNode = run.path("head_branch");
        String branch = branchNode.isMissingNode() || branchNode.isNull() ? null : text(branchNode);
        if (branch != null && (branch.isBlank() || branch.codePointCount(0, branch.length()) > 255
                || branch.codePoints().anyMatch(Character::isISOControl))) throw invalid();
        Instant updated = timestamp(run.path("updated_at"));
        JsonNode startedNode = run.path("run_started_at");
        Instant started = startedNode.isMissingNode() || startedNode.isNull() ? null : timestamp(startedNode);
        if (started != null && updated.isBefore(started)) throw invalid();
        String status = switch (conclusion) {
            case "success" -> "SUCCESS";
            case "failure" -> "FAILED";
            default -> CONCLUSIONS.contains(conclusion) ? conclusion.toUpperCase(java.util.Locale.ROOT) : "UNKNOWN";
        };
        return new Attempt(Long.toString(id.longValue()), attempt.intValue(), sha, branch, status, conclusion, started, updated);
    }

    void persist(UUID repository, Attempt attempt) {
        persist(repository, null, attempt);
    }

    void persist(UUID repository, UUID workspace, Attempt attempt) {
        if (attempt == null) {
            afterCommit("ignored");
            return;
        }
        List<Stored> stored = jdbc.query("""
                SELECT id,commit_sha,branch,status,provider_conclusion,started_at,provider_updated_at
                FROM ci_pipelines WHERE repository_id=? AND pipeline_id=? AND run_attempt=?
                """, (rs, n) -> new Stored(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), instant(rs.getTimestamp(6)), instant(rs.getTimestamp(7))),
                repository, attempt.runId(), attempt.number());
        if (stored.isEmpty()) {
            insert(repository, attempt);
            notifyLinkedTasks(repository, workspace, attempt.sha());
            afterCommit("accepted");
            return;
        }
        Stored previous = stored.get(0);
        boolean terminal = !Set.of("PENDING", "RUNNING").contains(previous.status());
        if (!previous.sha().equals(attempt.sha()) || (terminal && (!previous.status().equals(attempt.status())
                || (previous.conclusion() != null && !previous.conclusion().equals(attempt.conclusion())))))
            throw conflict();
        int order = previous.updated() == null ? 1 : attempt.updated().compareTo(previous.updated());
        if (order == 0 && (!Objects.equals(previous.branch(), attempt.branch())
                || !Objects.equals(previous.started(), attempt.started()) || !terminal
                || !Objects.equals(previous.conclusion(), attempt.conclusion()))) throw conflict();
        if (order > 0) {
            jdbc.update("""
                    UPDATE ci_pipelines SET branch=?,status=?,provider_status='completed',provider_conclusion=?,
                        started_at=?,finished_at=?,provider_updated_at=?,updated_at=CURRENT_TIMESTAMP WHERE id=?
                    """, attempt.branch(), attempt.status(), attempt.conclusion(), sqlTime(attempt.started()),
                    sqlTime(attempt.updated()), sqlTime(attempt.updated()), previous.id());
            if (!terminal && attempt.status().equals("FAILED")) enqueue(repository, previous.id());
            notifyLinkedTasks(repository, workspace, attempt.sha());
        }
        afterCommit(order > 0 ? "accepted" : "duplicate");
    }

    private void notifyLinkedTasks(UUID repository, UUID workspace, String sha) {
        if (events == null || workspace == null || sha == null) return;
        List<UUID> linked = jdbc.queryForList("""
                SELECT DISTINCT task_id FROM (
                    SELECT l.task_id
                    FROM git_commit_task_links l
                    JOIN git_commits c ON c.id = l.commit_id
                    WHERE c.repository_id = ? AND c.sha = ?
                    UNION ALL
                    SELECT pl.task_id
                    FROM git_pull_request_task_links pl
                    JOIN pull_requests pr ON pr.id = pl.pull_request_id
                    WHERE pr.repository_id = ? AND pr.head_sha = ?
                ) matched
                LIMIT 100
                """, UUID.class, repository, sha, repository, sha);
        if (!linked.isEmpty()) {
            GitWebhookIngestion.publishActivityAfterCommit(
                    events, metrics, workspace, repository, new LinkedHashSet<>(linked), ActivityKind.CI_UPDATED);
        }
    }

    private void insert(UUID repository, Attempt attempt) {
        UUID pipeline = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ci_pipelines(id,repository_id,pipeline_id,run_attempt,commit_sha,branch,status,
                    provider_status,provider_conclusion,started_at,finished_at,provider_updated_at)
                VALUES (?,?,?,?,?,?,?,'completed',?,?,?,?)
                """, pipeline, repository, attempt.runId(), attempt.number(), attempt.sha(), attempt.branch(),
                attempt.status(), attempt.conclusion(), sqlTime(attempt.started()), sqlTime(attempt.updated()),
                sqlTime(attempt.updated()));
        if (attempt.status().equals("FAILED")) enqueue(repository, pipeline);
    }

    private void enqueue(UUID repository, UUID pipeline) {
        // Serialize admissions across repositories so the shared backlog cap holds across instances.
        jdbc.query("SELECT pg_advisory_xact_lock(13724375,25)", (rs, n) -> 1);
        long global = jdbc.queryForObject("""
                SELECT count(*) FROM (
                    SELECT 1 FROM ci_log_fetch_jobs WHERE status IN ('PENDING','PROCESSING')
                    UNION ALL
                    SELECT 1 FROM ci_failure_event_outbox WHERE status IN ('PENDING','PROCESSING')
                    LIMIT ?
                ) backlog
                """, Long.class, properties.getGlobalBacklog());
        long local = jdbc.queryForObject("""
                SELECT count(*) FROM (
                    SELECT 1 FROM ci_pipelines p JOIN ci_log_fetch_jobs j ON j.pipeline_id=p.id
                        WHERE p.repository_id=? AND j.status IN ('PENDING','PROCESSING')
                    UNION ALL
                    SELECT 1 FROM ci_failure_event_outbox WHERE repository_id=? AND status IN ('PENDING','PROCESSING')
                    LIMIT ?
                ) backlog
                """, Long.class, repository, repository, properties.getRepositoryBacklog());
        if (global >= properties.getGlobalBacklog() || local >= properties.getRepositoryBacklog()) {
            metrics.counter("devflow.gitci.ci.webhook", "outcome", "backlog_full").increment();
            throw new WebhookFailure(503, "CI_LOG_BACKLOG_FULL");
        }
        List<UUID> failures = jdbc.query("""
                INSERT INTO ci_failures(pipeline_id,failure_key,stage_name,job_name,log_status)
                VALUES (?,'workflow-run','workflow','aggregate','PENDING')
                ON CONFLICT(pipeline_id,failure_key) DO NOTHING RETURNING id
                """, (rs, n) -> rs.getObject(1, UUID.class), pipeline);
        if (!failures.isEmpty()) jdbc.update("""
                INSERT INTO ci_log_fetch_jobs(pipeline_id,failure_id) VALUES (?,?)
                """, pipeline, failures.get(0));
    }

    private void afterCommit(String outcome) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { metrics.counter("devflow.gitci.ci.webhook", "outcome", outcome).increment(); }
        });
    }

    private WebhookFailure conflict() {
        metrics.counter("devflow.gitci.ci.webhook", "outcome", "conflict").increment();
        return new WebhookFailure(409, "CI_WORKFLOW_SNAPSHOT_CONFLICT");
    }

    private static String text(JsonNode node) {
        if (!node.isTextual()) throw invalid();
        return node.textValue();
    }

    static boolean validSha(String sha) {
        return sha.matches("([0-9a-f]{40}|[0-9a-f]{64})") && !sha.matches("0+");
    }

    private static Instant timestamp(JsonNode node) {
        String value = text(node);
        try {
            if (value.length() > 40) throw invalid();
            OffsetDateTime time = OffsetDateTime.parse(value);
            if (time.getYear() < 1970 || time.getYear() > 9999) throw invalid();
            return time.toInstant().truncatedTo(ChronoUnit.MICROS);
        } catch (java.time.DateTimeException ex) {
            throw invalid();
        }
    }

    static Timestamp sqlTime(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static WebhookFailure invalid() { return new WebhookFailure(400, "WEBHOOK_INVALID_PAYLOAD"); }
}
