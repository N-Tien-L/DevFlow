package io.devflow.gitci.internal;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
final class CiLogFetchWorker {
    private static final Logger log = LoggerFactory.getLogger(CiLogFetchWorker.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final GitHubActionsProperties properties;
    private final GitHubActionsReadClient github;
    private final GitHubWebhookProperties webhookProperties;
    private final CiLogSanitizer sanitizer;
    private final MeterRegistry metrics;
    private final java.util.concurrent.ExecutorService workers;
    private final Semaphore slots;

    CiLogFetchWorker(JdbcTemplate jdbc, PlatformTransactionManager manager, GitHubActionsProperties properties,
                     GitHubActionsReadClient github, GitHubWebhookProperties webhookProperties,
                     CiLogSanitizer sanitizer, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.transactions = ciTransactions(manager);
        this.properties = properties;
        this.github = github;
        this.webhookProperties = webhookProperties;
        this.sanitizer = sanitizer;
        this.metrics = metrics;
        slots = new Semaphore(properties.getSlots());
        workers = Executors.newFixedThreadPool(properties.getSlots(), task -> {
            Thread thread = new Thread(task, "ci-log-fetch");
            thread.setDaemon(true);
            return thread;
        });
        metrics.gauge("devflow.gitci.ci.fetch.depth", this, worker -> worker.activeDepth());
        metrics.gauge("devflow.gitci.ci.fetch.oldest.seconds", this, worker -> worker.oldestAge());
    }

    private record Claim(UUID pipeline, UUID failure, UUID workspace, UUID token, int attempts, int notFound,
                         GitHubActionsReadClient.Target target, Instant occurredAt) { }

    @Scheduled(fixedDelayString = "${devflow.gitci.ci.fetch-poll-ms:1000}")
    void poll() {
        if (!properties.isFetchEnabled()) return;
        for (int slot = 0; slot < properties.getSlots() && slots.tryAcquire(); slot++) {
            try {
                workers.execute(() -> {
                    try { runOne(); }
                    catch (RuntimeException ex) {
                        metrics.counter("devflow.gitci.ci.fetch.worker.errors").increment();
                        log.warn("CI fetch worker failed; the queue can recover expired claims");
                    } finally { slots.release(); }
                });
            } catch (RejectedExecutionException ex) {
                slots.release();
                return;
            }
        }
    }

    void runOne() {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("CI fetch requires an execution thread without a transaction");
        Claim claim = transactions.execute(status -> claim());
        if (claim == null) return;
        long start = System.nanoTime();
        try {
            long deadline = start + properties.budget().toNanos();
            var target = claim.target();
            var binding = webhookProperties.getBindings().get(target.repository());
            List<String> secrets = java.util.stream.Stream.of(properties.token(target.repository(), target.remoteId()),
                    binding == null ? null : binding.getCurrent(), binding == null ? null : binding.getPrevious())
                    .filter(java.util.Objects::nonNull).toList();
            CiLogSanitizer.Excerpt excerpt = sanitizer.extract(github.fetch(claim.target(), deadline), secrets, deadline);
            boolean saved = Boolean.TRUE.equals(transactions.execute(status -> complete(claim, excerpt)));
            outcome(saved ? "ready" : "stale", "NONE");
            if (saved) metrics.counter("devflow.gitci.ci.outbox.outcomes", "outcome", "enqueued").increment();
        } catch (GitHubActionsReadClient.FetchFailure ex) {
            String result = transactions.execute(status -> fail(claim, ex));
            outcome(result, ex.code);
            if (List.of("FAILED", "PERMISSION_DENIED", "UNAVAILABLE").contains(result))
                log.warn("CI log fetch stopped pipelineId={} outcome={} code={}", claim.pipeline(), result, ex.code);
        } finally { metrics.timer("devflow.gitci.ci.fetch.duration").record(System.nanoTime() - start, TimeUnit.NANOSECONDS); }
    }

    private Claim claim() {
        timeouts(jdbc);
        jdbc.update("""
                WITH spent AS (
                    SELECT j.pipeline_id FROM ci_log_fetch_jobs j JOIN ci_pipelines p ON p.id=j.pipeline_id
                    WHERE j.attempts=8 AND j.status='PROCESSING' AND j.lease_until<=CURRENT_TIMESTAMP
                    ORDER BY j.lease_until,j.pipeline_id LIMIT 16 FOR UPDATE OF p SKIP LOCKED
                ), failed AS (
                    UPDATE ci_log_fetch_jobs j SET status='FAILED',error_code='LEASE_RETRY_EXHAUSTED',
                        lease_until=NULL,claim_token=NULL FROM spent WHERE j.pipeline_id=spent.pipeline_id
                            AND j.attempts=8 AND j.status='PROCESSING' AND j.lease_until<=CURRENT_TIMESTAMP
                    RETURNING j.failure_id
                )
                UPDATE ci_failures SET log_status='FAILED',log_error_code='LEASE_RETRY_EXHAUSTED',
                    updated_at=CURRENT_TIMESTAMP WHERE id IN (SELECT failure_id FROM failed)
                """);
        List<Claim> claimed = jdbc.query("""
                WITH candidate AS (
                    SELECT j.pipeline_id FROM ci_log_fetch_jobs j JOIN ci_pipelines p ON p.id=j.pipeline_id
                    WHERE j.attempts<8 AND ((j.status='PENDING' AND j.next_attempt_at<=CURRENT_TIMESTAMP)
                        OR (j.status='PROCESSING' AND j.lease_until<=CURRENT_TIMESTAMP))
                    ORDER BY j.next_attempt_at,j.pipeline_id LIMIT 1 FOR UPDATE OF p SKIP LOCKED
                ), owned AS (
                    UPDATE ci_log_fetch_jobs j SET status='PROCESSING',claim_token=?,attempts=attempts+1,
                        lease_until=CURRENT_TIMESTAMP + (? * INTERVAL '1 second') FROM candidate
                    WHERE j.pipeline_id=candidate.pipeline_id AND j.attempts<8
                        AND ((j.status='PENDING' AND j.next_attempt_at<=CURRENT_TIMESTAMP)
                        OR (j.status='PROCESSING' AND j.lease_until<=CURRENT_TIMESTAMP)) RETURNING j.*
                )
                SELECT o.pipeline_id,o.failure_id,o.claim_token,o.attempts,o.not_found_attempts,r.id,r.workspace_id,
                    r.remote_repo_id,r.repository_url,p.pipeline_id AS run_id,p.run_attempt,p.commit_sha,p.finished_at
                FROM owned o JOIN ci_pipelines p ON p.id=o.pipeline_id JOIN repositories r ON r.id=p.repository_id
                """, (rs, n) -> new Claim(rs.getObject("pipeline_id", UUID.class), rs.getObject("failure_id", UUID.class),
                rs.getObject("workspace_id", UUID.class), rs.getObject("claim_token", UUID.class), rs.getInt("attempts"),
                rs.getInt("not_found_attempts"), new GitHubActionsReadClient.Target(rs.getObject("id", UUID.class),
                    rs.getString("remote_repo_id"), rs.getString("repository_url"), rs.getString("run_id"),
                    rs.getInt("run_attempt"), rs.getString("commit_sha")), rs.getTimestamp("finished_at").toInstant()),
                UUID.randomUUID(), properties.getLeaseSeconds());
        if (claimed.isEmpty()) return null;
        Claim claim = claimed.get(0);
        jdbc.update("UPDATE ci_failures SET log_status='FETCHING',log_error_code=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                claim.failure());
        return claim;
    }

    private boolean complete(Claim claim, CiLogSanitizer.Excerpt excerpt) {
        if (!lockAttempt(claim)) return false;
        var target = claim.target();
        if (!stillBound(claim)) {
            stop(claim, "UNAVAILABLE", "REPOSITORY_BINDING_CHANGED", 0, claim.notFound(), false);
            return false;
        }
        if (!finishJob(claim, "DONE", null, 0, claim.notFound(), false)) return false;
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                UPDATE ci_failures SET log_status='READY',log_ready_at=CURRENT_TIMESTAMP,
                    log_expires_at=CURRENT_TIMESTAMP + (? * INTERVAL '1 day'), raw_log_snippet=?,raw_log_reference=?,
                    error_summary=?,log_truncated=?,log_bytes=?,log_digest=?,log_event_id=?,log_error_code=NULL,updated_at=CURRENT_TIMESTAMP
                WHERE id=?
                """, properties.getRetentionDays(), excerpt.text(), "ci-log:" + claim.failure(), excerpt.summary(),
                excerpt.truncated(), excerpt.bytes(), excerpt.digest(), eventId, claim.failure());
        int marked = jdbc.update("UPDATE ci_pipelines SET failure_event_enqueued=TRUE WHERE id=? AND NOT failure_event_enqueued",
                claim.pipeline());
        if (marked != 1) throw new IllegalStateException("CI attempt already has an event marker");
        jdbc.update("""
                INSERT INTO ci_failure_event_outbox(id,pipeline_id,failure_id,repository_id,workspace_id,provider_run_id,
                    run_attempt,repo,commit_sha,occurred_at)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, eventId, claim.pipeline(), claim.failure(), target.repository(), claim.workspace(), target.runId(), target.attempt(),
                GitHubActionsReadClient.repositorySlug(target.url()), target.sha(), Timestamp.from(claim.occurredAt()));
        return true;
    }

    private String fail(Claim claim, GitHubActionsReadClient.FetchFailure error) {
        if (!lockAttempt(claim)) return "stale";
        int misses = claim.notFound() + (error.kind == GitHubActionsReadClient.Kind.NOT_FOUND ? 1 : 0);
        boolean circuit = error.kind == GitHubActionsReadClient.Kind.CIRCUIT_OPEN;
        String status = switch (error.kind) {
            case DENIED -> "PERMISSION_DENIED";
            case UNAVAILABLE -> "UNAVAILABLE";
            case NOT_FOUND -> misses >= 3 ? "UNAVAILABLE" : claim.attempts() >= 8 ? "FAILED" : "RETRY_WAIT";
            case TRANSIENT -> claim.attempts() >= 8 ? "FAILED" : "RETRY_WAIT";
            case CIRCUIT_OPEN -> "RETRY_WAIT";
        };
        return stop(claim, status, error.code, Math.max(error.delay, backoff(claim.attempts())), misses, circuit) ? status : "stale";
    }

    private boolean stop(Claim claim, String status, String code, long delay, int misses, boolean refund) {
        if (!finishJob(claim, status.equals("RETRY_WAIT") ? "PENDING" : "FAILED", code, delay, misses, refund)) return false;
        jdbc.update("UPDATE ci_failures SET log_status=?,log_error_code=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                status, code, claim.failure());
        return true;
    }

    private boolean finishJob(Claim claim, String status, String code, long delay, int misses, boolean refund) {
        return jdbc.update("""
                UPDATE ci_log_fetch_jobs SET status=?,error_code=?,next_attempt_at=CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),
                    not_found_attempts=?,attempts=attempts-?,claim_token=NULL,lease_until=NULL
                WHERE pipeline_id=? AND status='PROCESSING' AND claim_token=? AND lease_until>clock_timestamp()
                """, status, code, delay, misses, refund ? 1 : 0, claim.pipeline(), claim.token()) == 1;
    }

    private boolean lockAttempt(Claim claim) {
        timeouts(jdbc);
        return !jdbc.queryForList("SELECT id FROM ci_pipelines WHERE id=? FOR UPDATE", UUID.class, claim.pipeline()).isEmpty();
    }

    private boolean stillBound(Claim claim) {
        var target = claim.target();
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM repositories WHERE id=? AND workspace_id=? AND provider='GITHUB'
                    AND remote_repo_id=? AND repository_url=?)
                """, Boolean.class, target.repository(), claim.workspace(), target.remoteId(), target.url()));
    }

    private void outcome(String result, String safeCode) {
        metrics.counter("devflow.gitci.ci.fetch.outcomes", "outcome", result, "code", safeCode).increment();
    }
    private double activeDepth() {
        return jdbc.queryForObject("SELECT count(*) FROM ci_log_fetch_jobs WHERE status IN ('PENDING','PROCESSING')", Long.class);
    }
    private double oldestAge() {
        return jdbc.queryForObject("""
                SELECT COALESCE(EXTRACT(EPOCH FROM CURRENT_TIMESTAMP-min(created_at)),0)
                FROM ci_log_fetch_jobs WHERE status IN ('PENDING','PROCESSING')
                """, Double.class);
    }

    @PreDestroy
    void close() { workers.shutdownNow(); }

    static TransactionTemplate ciTransactions(PlatformTransactionManager manager) {
        var transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setTimeout(6);
        return transactions;
    }

    static void timeouts(JdbcTemplate jdbc) {
        jdbc.execute("SET LOCAL lock_timeout = '2s'");
        jdbc.execute("SET LOCAL statement_timeout = '4s'");
    }

    static long backoff(int attempts) {
        long base = Math.min(300, 5L << Math.max(0, attempts - 1));
        return Math.min(300, base + ThreadLocalRandom.current().nextLong(1 + base / 4));
    }
}
