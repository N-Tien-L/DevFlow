package io.devflow.gitci.internal;

import io.devflow.common.event.CiFailureDetectedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
final class CiFailureEventOutbox {
    private static final Logger log = LoggerFactory.getLogger(CiFailureEventOutbox.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final GitHubActionsProperties properties;
    private final ApplicationEventPublisher events;
    private final MeterRegistry metrics;

    CiFailureEventOutbox(JdbcTemplate jdbc, PlatformTransactionManager manager, GitHubActionsProperties properties,
                         ApplicationEventPublisher events, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.transactions = CiLogFetchWorker.ciTransactions(manager);
        this.properties = properties;
        this.events = events;
        this.metrics = metrics;
        metrics.gauge("devflow.gitci.ci.outbox.depth", this, outbox -> outbox.depth());
        metrics.gauge("devflow.gitci.ci.outbox.oldest.seconds", this, outbox -> outbox.oldestAge());
    }

    private record Claim(CiFailureDetectedEvent event, UUID token, int attempts) { }

    @Scheduled(fixedDelayString = "${devflow.gitci.ci.publish-poll-ms:1000}")
    void poll() {
        if (!properties.isPublishEnabled()) return;
        try { dispatch(); }
        catch (RuntimeException ex) {
            outcome("worker_error");
            log.warn("CI event outbox worker failed; durable rows remain recoverable");
        }
    }

    void dispatch() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("CI outbox publishing requires a thread without a transaction");
        Claim claim = transactions.execute(ignored -> claim());
        if (claim == null) return;
        boolean readable = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM ci_failure_event_outbox o JOIN ci_failures f ON f.id=o.failure_id
                    JOIN ci_pipelines p ON p.id=o.pipeline_id JOIN repositories r ON r.id=p.repository_id
                    WHERE o.id=? AND o.status='PROCESSING' AND o.claim_token=? AND o.lease_until>clock_timestamp()
                    AND r.id=o.repository_id AND r.workspace_id=o.workspace_id
                    AND f.log_status='READY' AND f.log_expires_at>clock_timestamp() AND f.log_event_id=o.id)
                """, Boolean.class, claim.event().eventId(), claim.token()));
        if (!readable) {
            update(claim, "FAILED", 0);
            return;
        }
        try { events.publishEvent(claim.event()); }
        catch (RuntimeException ex) {
            update(claim, claim.attempts() >= 8 ? "FAILED" : "PENDING", CiLogFetchWorker.backoff(claim.attempts()));
            return;
        }
        update(claim, "PUBLISHED", 0);
    }

    private Claim claim() {
        CiLogFetchWorker.timeouts(jdbc);
        int exhausted = jdbc.update("""
                UPDATE ci_failure_event_outbox SET status='FAILED',claim_token=NULL,lease_until=NULL
                WHERE id IN (SELECT id FROM ci_failure_event_outbox WHERE attempts=8 AND status='PROCESSING'
                    AND lease_until<=CURRENT_TIMESTAMP ORDER BY lease_until,id LIMIT 16 FOR UPDATE SKIP LOCKED)
                """);
        if (exhausted > 0) metrics.counter("devflow.gitci.ci.outbox.outcomes", "outcome", "failed").increment(exhausted);
        List<Claim> claims = jdbc.query("""
                UPDATE ci_failure_event_outbox SET status='PROCESSING',attempts=attempts+1,claim_token=?,
                    lease_until=CURRENT_TIMESTAMP + (? * INTERVAL '1 second')
                WHERE id=(SELECT id FROM ci_failure_event_outbox WHERE attempts<8 AND (
                    (status='PROCESSING' AND lease_until<=CURRENT_TIMESTAMP) OR
                    (status='PENDING' AND next_attempt_at<=CURRENT_TIMESTAMP))
                    ORDER BY next_attempt_at,id LIMIT 1 FOR UPDATE SKIP LOCKED)
                RETURNING *
                """, (rs, n) -> new Claim(new CiFailureDetectedEvent(rs.getString("pipeline_id"),
                    "ci-log:" + rs.getString("failure_id"), rs.getString("repo"), rs.getString("commit_sha"),
                    rs.getTimestamp("occurred_at").toInstant(), rs.getObject("id", UUID.class),
                    rs.getObject("repository_id", UUID.class), rs.getObject("workspace_id", UUID.class),
                    rs.getString("provider_run_id"), rs.getInt("run_attempt")),
                    rs.getObject("claim_token", UUID.class), rs.getInt("attempts")),
                UUID.randomUUID(), properties.getLeaseSeconds());
        return claims.isEmpty() ? null : claims.get(0);
    }

    private void update(Claim claim, String state, long delay) {
        Integer changed = transactions.execute(ignored -> {
            CiLogFetchWorker.timeouts(jdbc);
            return jdbc.update("""
                    UPDATE ci_failure_event_outbox SET status=?,claim_token=NULL,lease_until=NULL,
                        published_at=CASE WHEN ?='PUBLISHED' THEN CURRENT_TIMESTAMP ELSE NULL END,
                        next_attempt_at=CURRENT_TIMESTAMP + (? * INTERVAL '1 second')
                    WHERE id=? AND status='PROCESSING' AND claim_token=? AND lease_until>clock_timestamp()
                    """, state, state, delay, claim.event().eventId(), claim.token());
        });
        if (changed != null && changed == 1) {
            outcome(switch (state) { case "PUBLISHED" -> "published"; case "FAILED" -> "failed"; default -> "retry"; });
            if (state.equals("FAILED")) log.warn("CI event outbox stopped eventId={}", claim.event().eventId());
        }
    }

    @Scheduled(fixedDelayString = "${devflow.gitci.ci.retention-poll-ms:60000}")
    void purgeScheduled() {
        if (properties.isRetentionEnabled()) purge();
    }

    void purge() {
        try {
            transactions.executeWithoutResult(ignored -> {
                CiLogFetchWorker.timeouts(jdbc);
                int expired = jdbc.update("""
                        UPDATE ci_failures SET log_status='EXPIRED',raw_log_snippet=NULL,error_summary=NULL,
                            log_bytes=NULL,log_digest=NULL,updated_at=CURRENT_TIMESTAMP
                        WHERE id IN (SELECT f.id FROM ci_failures f WHERE f.log_status='READY'
                            AND f.log_expires_at<=CURRENT_TIMESTAMP AND NOT EXISTS (
                                SELECT 1 FROM ci_failure_event_outbox o WHERE o.id=f.log_event_id
                                    AND o.status IN ('PENDING','PROCESSING'))
                            ORDER BY f.log_expires_at,f.id LIMIT 100 FOR UPDATE OF f SKIP LOCKED)
                        """);
                jdbc.update("""
                        DELETE FROM ci_failure_event_outbox
                        WHERE id IN (SELECT id FROM ci_failure_event_outbox WHERE status='PUBLISHED'
                            AND published_at<=CURRENT_TIMESTAMP - (? * INTERVAL '1 day')
                            ORDER BY published_at,id LIMIT 100 FOR UPDATE SKIP LOCKED)
                        """, properties.getRetentionDays());
                if (expired > 0) metrics.counter("devflow.gitci.ci.retention.expired").increment(expired);
            });
        } catch (RuntimeException ex) {
            metrics.counter("devflow.gitci.ci.retention.errors").increment();
            log.warn("CI retention pass failed; will retry");
        }
    }

    private void outcome(String outcome) {
        metrics.counter("devflow.gitci.ci.outbox.outcomes", "outcome", outcome).increment();
    }
    private double depth() {
        return jdbc.queryForObject("SELECT count(*) FROM ci_failure_event_outbox WHERE status IN ('PENDING','PROCESSING')", Long.class);
    }
    private double oldestAge() {
        return jdbc.queryForObject("""
                SELECT COALESCE(EXTRACT(EPOCH FROM CURRENT_TIMESTAMP-min(created_at)),0)
                FROM ci_failure_event_outbox WHERE status IN ('PENDING','PROCESSING')
                """, Double.class);
    }
}
