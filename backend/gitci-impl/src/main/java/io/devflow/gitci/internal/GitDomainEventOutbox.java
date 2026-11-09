package io.devflow.gitci.internal;

import io.devflow.common.event.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable, at-least-once transport for the three Git business events. No payload text is logged. */
@Component
public class GitDomainEventOutbox {
    private static final Logger log = LoggerFactory.getLogger(GitDomainEventOutbox.class);
    private static final int BATCH_SIZE = 50;
    private static final int MAX_ATTEMPTS = 8;
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher publisher;
    private final MeterRegistry metrics;
    private final TransactionTemplate transactions;
    private final TransactionTemplate outsideTransaction;
    private final boolean workerEnabled;

    public record Entry(UUID id, UUID taskId, String type, String key, String repo, String url,
                        String author, Instant occurredAt, UUID token, int attempts) {
        DevFlowEvent event() {
            return switch (type) {
                case EventTypes.GIT_COMMIT_LINKED -> new GitCommitLinkedEvent(taskId, key, repo, author, occurredAt);
                case EventTypes.GIT_PR_OPENED -> new GitPrOpenedEvent(taskId, url, repo, null, occurredAt);
                case EventTypes.GIT_PR_MERGED -> new GitPrMergedEvent(taskId, url, repo, null, occurredAt);
                default -> throw new IllegalStateException("Unsupported Git event type");
            };
        }
    }

    public GitDomainEventOutbox(JdbcTemplate jdbc, PlatformTransactionManager manager,
            ApplicationEventPublisher publisher, MeterRegistry metrics,
            @Value("${devflow.gitci.outbox.worker-enabled:true}") boolean workerEnabled) {
        this.jdbc = jdbc; this.publisher = publisher; this.metrics = metrics; this.workerEnabled = workerEnabled;
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setTimeout(6);
        outsideTransaction = new TransactionTemplate(manager);
        outsideTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        for (String state : List.of("PENDING", "PROCESSING", "FAILED")) {
            metrics.gauge("devflow.gitci.outbox.depth", List.of(io.micrometer.core.instrument.Tag.of("status", state)),
                    this, queue -> queue.depth(state));
        }
        metrics.gauge("devflow.gitci.outbox.oldest.pending.seconds", this, GitDomainEventOutbox::oldestPendingSeconds);
    }

    record Pending(UUID repository, UUID task, String type, String key, String repo,
                   String url, String author, Instant occurredAt) { }

    /** Must join the webhook transaction; metrics count committed inserts only. */
    void enqueue(List<Pending> pending) {
        if (pending.isEmpty()) return;
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Git outbox requires a transaction");
        int[][] counts = jdbc.batchUpdate("""
                INSERT INTO git_domain_event_outbox(id,repository_id,task_id,event_type,git_object_key,repo,pr_url,author,occurred_at)
                VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT(repository_id,event_type,git_object_key,task_id) DO NOTHING
                """, pending, 200, (ps, row) -> {
            ps.setObject(1, UUID.randomUUID()); ps.setObject(2, row.repository()); ps.setObject(3, row.task());
            ps.setString(4, row.type()); ps.setString(5, row.key()); ps.setString(6, row.repo());
            ps.setString(7, row.url()); ps.setString(8, row.author()); ps.setTimestamp(9, Timestamp.from(row.occurredAt()));
        });
        var inserted = new java.util.HashMap<String, Integer>();
        int index = 0;
        for (int[] batch : counts) for (int count : batch) {
            if (count > 0 || count == java.sql.Statement.SUCCESS_NO_INFO)
                inserted.merge(pending.get(index).type(), 1, Integer::sum);
            index++;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                inserted.forEach((type, count) -> metric(type, "enqueued", count));
            }
        });
    }

    @Scheduled(fixedDelayString = "${devflow.gitci.outbox.poll-interval-ms:1000}")
    void poll() {
        if (!workerEnabled) return;
        try { dispatch(); }
        catch (RuntimeException ex) {
            metrics.counter("devflow.gitci.outbox.worker.errors").increment();
            log.warn("Git outbox worker failed; durable rows remain recoverable");
        }
    }

    /** Also usable by internal operational tooling/tests while scheduled polling is paused. */
    public void dispatch() {
        outsideTransaction.executeWithoutResult(ignored -> {
            for (Entry entry : claim()) publish(entry);
        });
    }

    public List<Entry> claim() {
        return transactions.execute(ignored -> {
            jdbc.execute("SET LOCAL lock_timeout = '2s'");
            jdbc.execute("SET LOCAL statement_timeout = '4s'");
            var exhausted = jdbc.queryForList("""
                    UPDATE git_domain_event_outbox SET status='FAILED',claim_token=NULL,lease_until=NULL
                    WHERE id IN (SELECT id FROM git_domain_event_outbox WHERE attempts>=8
                        AND ((status='PROCESSING' AND lease_until<=CURRENT_TIMESTAMP) OR status='PENDING')
                        LIMIT 50 FOR UPDATE SKIP LOCKED) RETURNING event_type
                    """, String.class);
            exhausted.forEach(type -> { metric(type, "failed", 1); log.warn("Git outbox exhausted retry budget type={}", type); });
            return jdbc.query("""
                    UPDATE git_domain_event_outbox o SET status='PROCESSING',claim_token=?,
                        lease_until=CURRENT_TIMESTAMP + INTERVAL '60 seconds',attempts=attempts+1
                    WHERE id IN (SELECT id FROM git_domain_event_outbox WHERE attempts<8 AND
                        ((status='PENDING' AND next_attempt_at<=CURRENT_TIMESTAMP) OR
                         (status='PROCESSING' AND lease_until<=CURRENT_TIMESTAMP))
                        ORDER BY created_at,id LIMIT ? FOR UPDATE SKIP LOCKED)
                    RETURNING o.*
                    """, (rs, n) -> new Entry(rs.getObject("id", UUID.class), rs.getObject("task_id", UUID.class),
                    rs.getString("event_type"), rs.getString("git_object_key"), rs.getString("repo"), rs.getString("pr_url"),
                    rs.getString("author"), rs.getTimestamp("occurred_at").toInstant(), rs.getObject("claim_token", UUID.class),
                    rs.getInt("attempts")), UUID.randomUUID(), BATCH_SIZE);
        });
    }

    public void publish(Entry entry) {
        outsideTransaction.executeWithoutResult(ignored -> publishOutsideTransaction(entry));
    }

    private void publishOutsideTransaction(Entry entry) {
        Integer owned = jdbc.queryForObject("""
                SELECT count(*) FROM git_domain_event_outbox WHERE id=? AND status='PROCESSING'
                    AND claim_token=? AND lease_until>CURRENT_TIMESTAMP
                """, Integer.class, entry.id(), entry.token());
        if (owned == null || owned == 0) return; // Deleted, expired or reclaimed; do not publish stale claims.
        long start = System.nanoTime();
        try {
            publisher.publishEvent(entry.event());
            int changed = jdbc.update("""
                    UPDATE git_domain_event_outbox SET status='PUBLISHED',published_at=CURRENT_TIMESTAMP,
                        claim_token=NULL,lease_until=NULL WHERE id=? AND status='PROCESSING' AND claim_token=?
                    """, entry.id(), entry.token());
            if (changed == 1) metric(entry.type(), "published", 1);
        } catch (RuntimeException ex) {
            boolean failed = entry.attempts() >= MAX_ATTEMPTS;
            long delay = ThreadLocalRandom.current().nextLong(backoffSeconds(entry.attempts()), backoffSeconds(entry.attempts()) * 2 + 1);
            int changed = jdbc.update("""
                    UPDATE git_domain_event_outbox SET status=?,next_attempt_at=CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),
                        claim_token=NULL,lease_until=NULL WHERE id=? AND status='PROCESSING' AND claim_token=?
                    """, failed ? "FAILED" : "PENDING", delay, entry.id(), entry.token());
            if (changed == 1) {
                metric(entry.type(), failed ? "failed" : "retry", 1);
                log.warn("Git outbox delivery deferred id={} type={} attempts={} failed={}", entry.id(), entry.type(), entry.attempts(), failed);
            }
        } finally {
            metrics.timer("devflow.gitci.outbox.publish.duration", "type", entry.type())
                    .record(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }

    static long backoffSeconds(int attempts) { return Math.min(300, 1L << Math.min(8, Math.max(0, attempts - 1))); }

    /** No public HTTP endpoint. Requeue preserves the immutable event snapshot. */
    public boolean requeue(UUID id) {
        return jdbc.update("UPDATE git_domain_event_outbox SET status='PENDING',attempts=0,next_attempt_at=CURRENT_TIMESTAMP WHERE id=? AND status='FAILED'", id) == 1;
    }

    @Scheduled(cron = "${devflow.gitci.outbox.cleanup-cron:0 15 4 * * *}", zone = "UTC")
    public void clean() {
        jdbc.update("""
                DELETE FROM git_domain_event_outbox WHERE id IN
                    (SELECT id FROM git_domain_event_outbox WHERE status='PUBLISHED'
                     AND published_at<CURRENT_TIMESTAMP - INTERVAL '7 days'
                     ORDER BY published_at LIMIT 1000 FOR UPDATE SKIP LOCKED)
                """);
    }

    private void metric(String type, String outcome, int count) {
        metrics.counter("devflow.gitci.outbox.events", "type", type, "outcome", outcome).increment(count);
    }
    private double depth(String status) {
        try { return jdbc.queryForObject("SELECT count(*) FROM git_domain_event_outbox WHERE status=?", Long.class, status); }
        catch (RuntimeException ex) { return Double.NaN; }
    }
    private double oldestPendingSeconds() {
        try { return jdbc.queryForObject("SELECT COALESCE(GREATEST(0,EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP-min(created_at)))),0) FROM git_domain_event_outbox WHERE status IN ('PENDING','PROCESSING')", Double.class); }
        catch (RuntimeException ex) { return Double.NaN; }
    }
}
