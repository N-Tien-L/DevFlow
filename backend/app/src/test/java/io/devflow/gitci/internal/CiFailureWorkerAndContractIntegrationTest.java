package io.devflow.gitci.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.devflow.common.event.CiFailureDetectedEvent;
import io.devflow.common.event.CiFailureLogReadRequestedEvent;
import io.devflow.common.event.CiFailureLogReadRequestedEvent.Status;
import io.devflow.common.event.WorkspaceDeletedEvent;
import io.devflow.gitci.internal.GitHubActionsReadClient.FetchFailure;
import io.devflow.gitci.internal.GitHubActionsReadClient.Kind;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CiFailureWorkerAndContractIntegrationTest {
    private static final UUID REPOSITORY = UUID.fromString("3d291192-72d4-432b-b3d9-2f4477101f11");
    private static final UUID REPOSITORY_B = UUID.fromString("4e3a22a3-83e5-443c-c4ea-305588212022");
    private static final String SHA = "c".repeat(40);
    private static final String SECRET_CANARY = "github_pat_worker_canary_never_logged_1234";
    private static final String SIGNED_URL_CANARY = "https://results-receiver.actions.githubusercontent.com/log?sig=secret_sig_999";

    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17-alpine");

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager txManager;
    @Autowired ApplicationEventPublisher events;
    @Autowired CiLogFetchWorker fetch;
    @Autowired CiFailureEventOutbox outbox;
    @Autowired GitHubActionsProperties ciProperties;
    @Autowired MeterRegistry metrics;
    @Autowired ProbeConsumer consumer;
    @MockitoBean GitHubActionsReadClient github;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private UUID workspace;
    private UUID workspaceB;

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DB::getJdbcUrl);
        properties.add("spring.datasource.username", DB::getUsername);
        properties.add("spring.datasource.password", DB::getPassword);
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("spring.flyway.enabled", () -> "true");
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @TestConfiguration
    static class Config {
        @Bean
        ProbeConsumer probeConsumer(ApplicationEventPublisher events, JdbcTemplate jdbc) {
            return new ProbeConsumer(events, jdbc);
        }
    }

    static final class ProbeConsumer {
        final ApplicationEventPublisher events;
        final JdbcTemplate jdbc;
        final java.util.ArrayList<CiFailureDetectedEvent> received = new java.util.ArrayList<>();
        final Set<UUID> inbox = new HashSet<>();
        final AtomicInteger inboxProcessed = new AtomicInteger();
        final AtomicInteger downstreamCompleted = new AtomicInteger();
        boolean alwaysFail;
        boolean swallowAndSkipCompletion;
        boolean deleteRepoBeforeRead;
        CiFailureLogReadRequestedEvent.Response lastRead;

        ProbeConsumer(ApplicationEventPublisher events, JdbcTemplate jdbc) {
            this.events = events;
            this.jdbc = jdbc;
        }

        void reset() {
            received.clear();
            inbox.clear();
            inboxProcessed.set(0);
            downstreamCompleted.set(0);
            alwaysFail = false;
            swallowAndSkipCompletion = false;
            deleteRepoBeforeRead = false;
            lastRead = null;
        }

        @EventListener
        void onFailure(CiFailureDetectedEvent event) {
            received.add(event);
            if (alwaysFail) {
                throw new IllegalStateException("synthetic listener outage");
            }
            if (swallowAndSkipCompletion) {
                // Demonstrates EVT-05: listener swallows/defers without completing downstream AI work.
                return;
            }
            if (deleteRepoBeforeRead) {
                jdbc.update("DELETE FROM repositories WHERE id=?", event.repositoryId());
            }
            var request = CiFailureLogReadRequestedEvent.forDetection(UUID.randomUUID(), event, 16_384);
            events.publishEvent(request);
            lastRead = request.response();
            if (inbox.add(event.eventId())) {
                inboxProcessed.incrementAndGet();
            }
            if (lastRead.status() == Status.READY) {
                downstreamCompleted.incrementAndGet();
            }
        }
    }

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM repositories");
        workspace = UUID.randomUUID();
        workspaceB = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO repositories(id,workspace_id,provider,remote_repo_id,name,repository_url,webhook_secret_ref)
                VALUES (?,?,'GITHUB','501','repo-a','https://github.com/example/repo-a','ref:a'),
                       (?,?,'GITHUB','502','repo-b','https://github.com/example/repo-b','ref:b')
                """, REPOSITORY, workspace, REPOSITORY_B, workspaceB);
        ciProperties.setFetchEnabled(false);
        ciProperties.setPublishEnabled(false);
        consumer.reset();
    }

    @Test
    void handlesTransientRetriesThrottleDelayNotFoundBudgetCircuitRefundUnavailableAndRunbookRequeue() throws Exception {
        // LOG-10 & LOG-11: transient retry -> throttle delay -> recovery
        UUID p1 = seedPendingFailure(REPOSITORY, "1001", 1);
        when(github.fetch(any(), anyLong()))
                .thenThrow(new FetchFailure(Kind.TRANSIENT, "GITHUB_UPSTREAM"))
                .thenThrow(new FetchFailure(Kind.TRANSIENT, "GITHUB_THROTTLED", 120))
                .thenReturn(archive("Error: recovered after throttle"));
        fetch.runOne();
        assertThat(logStatus(p1)).isEqualTo("RETRY_WAIT");
        assertThat(jobMap(p1)).containsEntry("status", "PENDING").containsEntry("attempts", 1);
        makeJobDue(p1);
        fetch.runOne();
        assertThat(logStatus(p1)).isEqualTo("RETRY_WAIT");
        Instant nextAttempt = jdbc.queryForObject(
                "SELECT next_attempt_at FROM ci_log_fetch_jobs WHERE pipeline_id=?", Timestamp.class, p1).toInstant();
        assertThat(nextAttempt).isAfter(Instant.now().plusSeconds(100));
        makeJobDue(p1);
        fetch.runOne();
        assertThat(logStatus(p1)).isEqualTo("READY");
        assertThat(count("ci_failure_event_outbox")).isOne();

        // LOG-10 exhaustion: 8 transient failures -> FAILED
        UUID pExhaust = seedPendingFailure(REPOSITORY, "1002", 1);
        reset(github);
        when(github.fetch(any(), anyLong())).thenThrow(new FetchFailure(Kind.TRANSIENT, "GITHUB_NETWORK"));
        for (int attempt = 1; attempt <= 8; attempt++) {
            makeJobDue(pExhaust);
            fetch.runOne();
        }
        assertThat(logStatus(pExhaust)).isEqualTo("FAILED");
        assertThat(jobMap(pExhaust)).containsEntry("status", "FAILED").containsEntry("attempts", 8);

        // LOG-13 temporary 404 (2x) then 200 -> READY
        UUID pTemp404 = seedPendingFailure(REPOSITORY, "1003", 1);
        reset(github);
        when(github.fetch(any(), anyLong()))
                .thenThrow(new FetchFailure(Kind.NOT_FOUND, "ARCHIVE_NOT_FOUND"))
                .thenThrow(new FetchFailure(Kind.NOT_FOUND, "ARCHIVE_NOT_FOUND"))
                .thenReturn(archive("Error: archive appeared on 3rd try"));
        makeJobDue(pTemp404); fetch.runOne();
        assertThat(logStatus(pTemp404)).isEqualTo("RETRY_WAIT");
        makeJobDue(pTemp404); fetch.runOne();
        assertThat(logStatus(pTemp404)).isEqualTo("RETRY_WAIT");
        makeJobDue(pTemp404); fetch.runOne();
        assertThat(logStatus(pTemp404)).isEqualTo("READY");

        // LOG-13 persistent 404 (3x) -> UNAVAILABLE (not FAILED) with 0 outbox
        UUID pPerm404 = seedPendingFailure(REPOSITORY, "1004", 1);
        reset(github);
        when(github.fetch(any(), anyLong())).thenThrow(new FetchFailure(Kind.NOT_FOUND, "ARCHIVE_NOT_FOUND"));
        for (int i = 0; i < 3; i++) {
            makeJobDue(pPerm404);
            fetch.runOne();
        }
        assertThat(logStatus(pPerm404)).isEqualTo("UNAVAILABLE");
        assertThat(jobMap(pPerm404))
                .containsEntry("status", "FAILED")
                .containsEntry("not_found_attempts", 3)
                .containsEntry("error_code", "ARCHIVE_NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ci_failure_event_outbox WHERE pipeline_id=?", Long.class, pPerm404))
                .isZero();

        // LOG-15 CIRCUIT_OPEN -> RETRY_WAIT and refunds attempt so retry budget is not burned
        UUID pCircuit = seedPendingFailure(REPOSITORY, "1005", 1);
        reset(github);
        when(github.fetch(any(), anyLong())).thenThrow(new FetchFailure(Kind.CIRCUIT_OPEN, "CIRCUIT_OPEN", 30));
        makeJobDue(pCircuit);
        fetch.runOne();
        assertThat(logStatus(pCircuit)).isEqualTo("RETRY_WAIT");
        assertThat(jobMap(pCircuit)).containsEntry("status", "PENDING").containsEntry("attempts", 0);

        // LOG-09 corrupt archive -> UNAVAILABLE
        UUID pCorrupt = seedPendingFailure(REPOSITORY, "1006", 1);
        reset(github);
        when(github.fetch(any(), anyLong())).thenReturn(
                new GitHubActionsReadClient.Archive("bad-zip".getBytes(StandardCharsets.UTF_8), List.of(), false));
        makeJobDue(pCorrupt);
        fetch.runOne();
        assertThat(logStatus(pCorrupt)).isEqualTo("UNAVAILABLE");
        assertThat(jobMap(pCorrupt)).containsEntry("error_code", "ARCHIVE_INVALID");

        // LOG-12 + OPS-04: ACTIONS_READ_DENIED -> PERMISSION_DENIED -> exact runbook SQL requeue -> READY
        UUID pDenied = seedPendingFailure(REPOSITORY, "1007", 1);
        reset(github);
        when(github.fetch(any(), anyLong()))
                .thenThrow(new FetchFailure(Kind.DENIED, "ACTIONS_READ_DENIED"))
                .thenReturn(archive("Error: recovered via runbook requeue"));
        makeJobDue(pDenied);
        fetch.runOne();
        assertThat(logStatus(pDenied)).isEqualTo("PERMISSION_DENIED");
        assertThat(jobMap(pDenied)).containsEntry("status", "FAILED").containsEntry("error_code", "ACTIONS_READ_DENIED");

        // Run exact runbook fetch-job requeue SQL (15_tiep_nhan_loi_ci_operations.md)
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL lock_timeout = '2s'");
            jdbc.execute("SET LOCAL statement_timeout = '4s'");
            jdbc.queryForList("""
                    SELECT p.id FROM ci_pipelines p JOIN repositories r ON r.id = p.repository_id
                    WHERE p.id = ? AND r.workspace_id = ? FOR UPDATE OF p
                    """, UUID.class, pDenied, workspace);
            jdbc.query("SELECT pg_advisory_xact_lock(13724375,25)", (rs, n) -> 1);
            jdbc.update("""
                    WITH requeued AS (
                        UPDATE ci_log_fetch_jobs j
                        SET status='PENDING', attempts=0, not_found_attempts=0, error_code=NULL, next_attempt_at=now()
                        FROM ci_pipelines p, repositories r, ci_failures f
                        WHERE j.pipeline_id=p.id AND r.id=p.repository_id AND f.id=j.failure_id
                            AND p.id=? AND r.workspace_id=?
                            AND j.status='FAILED' AND j.claim_token IS NULL AND j.lease_until IS NULL
                            AND f.log_status IN ('FAILED','UNAVAILABLE','PERMISSION_DENIED') AND NOT p.failure_event_enqueued
                        RETURNING j.failure_id
                    )
                    UPDATE ci_failures SET log_status='PENDING',log_error_code=NULL,updated_at=now()
                    WHERE id IN (SELECT failure_id FROM requeued)
                    """, pDenied, workspace);
        });
        assertThat(logStatus(pDenied)).isEqualTo("PENDING");
        assertThat(jobMap(pDenied)).containsEntry("status", "PENDING").containsEntry("attempts", 0);
        // Move other PENDING job (pCircuit) into future so fetch.runOne picks pDenied deterministically
        jdbc.update("UPDATE ci_log_fetch_jobs SET next_attempt_at=now()+interval '1 hour' WHERE pipeline_id<>?", pDenied);
        fetch.runOne();
        assertThat(logStatus(pDenied)).isEqualTo("READY");
    }

    @Test
    void networkRunsOutsideDbLocksConcurrentWorkersClaimDistinctJobsAndStaleFailIsFenced() throws Exception {
        // TX-03 & TX-04: 2 pending jobs, 2 workers claim simultaneously via FOR UPDATE SKIP LOCKED
        UUID p1 = seedPendingFailure(REPOSITORY, "2001", 1);
        UUID p2 = seedPendingFailure(REPOSITORY, "2002", 1);
        var bothFetching = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var tokens = new java.util.concurrent.ConcurrentHashMap<UUID, UUID>();
        when(github.fetch(any(), anyLong())).thenAnswer(call -> {
            GitHubActionsReadClient.Target target = call.getArgument(0);
            UUID pid = jdbc.queryForObject("SELECT id FROM ci_pipelines WHERE pipeline_id=?", UUID.class, target.runId());
            UUID token = jdbc.queryForObject("SELECT claim_token FROM ci_log_fetch_jobs WHERE pipeline_id=?", UUID.class, pid);
            tokens.put(pid, token);
            bothFetching.countDown();
            if (!release.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("latch timeout");
            return archive("Error: concurrent worker result for " + target.runId());
        });

        var pool = Executors.newFixedThreadPool(2);
        try {
            var w1 = pool.submit(fetch::runOne);
            var w2 = pool.submit(fetch::runOne);
            assertThat(bothFetching.await(8, TimeUnit.SECONDS)).isTrue();
            assertThat(tokens).hasSize(2);
            assertThat(tokens.get(p1)).isNotEqualTo(tokens.get(p2));

            // TX-03: independent DB connection can lock repositories and ci_pipelines NOWAIT while fetch is in-flight!
            try (var conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                try (var st = conn.prepareStatement("SELECT id FROM repositories WHERE id=? FOR UPDATE NOWAIT")) {
                    st.setObject(1, REPOSITORY);
                    try (var rs = st.executeQuery()) { assertThat(rs.next()).isTrue(); }
                }
                try (var st = conn.prepareStatement("SELECT id FROM ci_pipelines WHERE id=? FOR UPDATE NOWAIT")) {
                    st.setObject(1, p1);
                    try (var rs = st.executeQuery()) { assertThat(rs.next()).isTrue(); }
                }
                conn.rollback();
            }

            release.countDown();
            w1.get(8, TimeUnit.SECONDS);
            w2.get(8, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(logStatus(p1)).isEqualTo("READY");
        assertThat(logStatus(p2)).isEqualTo("READY");

        // TX-04 lease exhaustion at attempts=8 -> LEASE_RETRY_EXHAUSTED
        UUID pLeaseSpent = seedPendingFailure(REPOSITORY, "2003", 1);
        jdbc.update("""
                UPDATE ci_log_fetch_jobs SET status='PROCESSING', attempts=8, claim_token=?,
                    lease_until=now() - interval '5 seconds' WHERE pipeline_id=?
                """, UUID.randomUUID(), pLeaseSpent);
        fetch.runOne();
        assertThat(logStatus(pLeaseSpent)).isEqualTo("FAILED");
        assertThat(jobMap(pLeaseSpent)).containsEntry("status", "FAILED").containsEntry("error_code", "LEASE_RETRY_EXHAUSTED");

        // TX-05: stale owner failing after new owner claimed cannot mutate new owner's state
        UUID pStaleFail = seedPendingFailure(REPOSITORY, "2004", 1);
        var entered = new CountDownLatch(1);
        var failNow = new CountDownLatch(1);
        reset(github);
        when(github.fetch(any(), anyLong()))
                .thenAnswer(call -> {
                    entered.countDown();
                    if (!failNow.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
                    throw new FetchFailure(Kind.DENIED, "ACTIONS_READ_DENIED");
                })
                .thenReturn(archive("Error: fresh owner succeeded"));
        var single = Executors.newSingleThreadExecutor();
        try {
            var staleWorker = single.submit(fetch::runOne);
            assertThat(entered.await(8, TimeUnit.SECONDS)).isTrue();
            jdbc.update("UPDATE ci_log_fetch_jobs SET lease_until=now() - interval '1 second' WHERE pipeline_id=?", pStaleFail);
            fetch.runOne();
            assertThat(logStatus(pStaleFail)).isEqualTo("READY");
            failNow.countDown();
            staleWorker.get(8, TimeUnit.SECONDS);
            // Fresh owner's READY state and DONE job must remain untouched
            assertThat(logStatus(pStaleFail)).isEqualTo("READY");
            assertThat(jobMap(pStaleFail)).containsEntry("status", "DONE");
        } finally {
            failNow.countDown();
            single.shutdownNow();
        }
    }

    @Test
    void outboxHandlesCrashRedeliveryExhaustionRunbookRequeuePauseResumeAndConsumerSemantics() throws Exception {
        // EVT-02: simulated crash after publish before mark PUBLISHED -> same eventId redelivered, inbox dedupes to 1
        UUID p1 = seedPendingFailure(REPOSITORY, "3001", 1);
        when(github.fetch(any(), anyLong())).thenReturn(archive("Error: crash redelivery test"));
        fetch.runOne();
        UUID originalEventId = jdbc.queryForObject(
                "SELECT id FROM ci_failure_event_outbox WHERE pipeline_id=?", UUID.class, p1);
        outbox.dispatch();
        assertThat(consumer.received).hasSize(1);
        assertThat(consumer.inboxProcessed.get()).isEqualTo(1);
        // Simulate crash before mark PUBLISHED by putting row back to expired PROCESSING lease (same eventId)
        jdbc.update("""
                UPDATE ci_failure_event_outbox SET status='PROCESSING', published_at=NULL,
                    claim_token=?, lease_until=now()-interval '2 seconds', attempts=1 WHERE id=?
                """, UUID.randomUUID(), originalEventId);
        outbox.dispatch();
        assertThat(consumer.received).hasSize(2);
        assertThat(consumer.received.get(0).eventId()).isEqualTo(originalEventId);
        assertThat(consumer.received.get(1).eventId()).isEqualTo(originalEventId);
        assertThat(consumer.inboxProcessed.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM ci_failure_event_outbox WHERE id=?", String.class, originalEventId))
                .isEqualTo("PUBLISHED");

        // EVT-03 + OPS-04: 8 publish failures -> FAILED -> exact runbook outbox requeue SQL keeps same eventId -> PUBLISHED
        UUID p2 = seedPendingFailure(REPOSITORY, "3002", 1);
        fetch.runOne();
        UUID failedEventId = jdbc.queryForObject(
                "SELECT id FROM ci_failure_event_outbox WHERE pipeline_id=?", UUID.class, p2);
        consumer.reset();
        consumer.alwaysFail = true;
        double failedBefore = metrics.counter("devflow.gitci.ci.outbox.outcomes", "outcome", "failed").count();
        for (int i = 0; i < 8; i++) {
            jdbc.update("UPDATE ci_failure_event_outbox SET next_attempt_at=now()-interval '1 second' WHERE id=?", failedEventId);
            outbox.dispatch();
        }
        assertThat(jdbc.queryForObject("SELECT status FROM ci_failure_event_outbox WHERE id=?", String.class, failedEventId))
                .isEqualTo("FAILED");
        assertThat(metrics.counter("devflow.gitci.ci.outbox.outcomes", "outcome", "failed").count())
                .isGreaterThan(failedBefore);

        // Execute exact runbook outbox requeue SQL (15_tiep_nhan_loi_ci_operations.md)
        UUID requeuedId = jdbc.queryForObject("""
                UPDATE ci_failure_event_outbox o
                SET status='PENDING',attempts=0,next_attempt_at=now()
                FROM ci_failures f, ci_pipelines p, repositories r
                WHERE o.pipeline_id=? AND o.workspace_id=?
                    AND o.status='FAILED' AND o.claim_token IS NULL AND o.lease_until IS NULL
                    AND o.pipeline_id=p.id AND r.id=p.repository_id AND r.id=o.repository_id AND r.workspace_id=o.workspace_id
                    AND f.id=o.failure_id AND f.log_event_id=o.id AND f.log_status='READY' AND f.log_expires_at>now()
                RETURNING o.id
                """, UUID.class, p2, workspace);
        assertThat(requeuedId).isEqualTo(failedEventId);
        consumer.alwaysFail = false;
        outbox.dispatch();
        assertThat(jdbc.queryForObject("SELECT status FROM ci_failure_event_outbox WHERE id=?", String.class, failedEventId))
                .isEqualTo("PUBLISHED");
        assertThat(consumer.received).last().extracting(CiFailureDetectedEvent::eventId).isEqualTo(failedEventId);

        // EVT-04: pause switches stop poll() and resume processes without backfilling LEGACY V6 rows
        UUID legacyPipeline = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ci_pipelines(id,repository_id,pipeline_id,run_attempt,commit_sha,branch,status,finished_at)
                VALUES (?,?,'legacy-99',1,?,'main','FAILED',now())
                """, legacyPipeline, REPOSITORY, SHA);
        jdbc.update("""
                INSERT INTO ci_failures(pipeline_id,failure_key,stage_name,job_name,log_status,raw_log_snippet)
                VALUES (?,'workflow-run','workflow','aggregate','LEGACY','legacy V6 text')
                """, legacyPipeline);
        UUID pPaused = seedPendingFailure(REPOSITORY, "3003", 1);
        ciProperties.setFetchEnabled(false);
        ciProperties.setPublishEnabled(false);
        fetch.poll();
        outbox.poll();
        assertThat(logStatus(pPaused)).isEqualTo("PENDING");
        ciProperties.setFetchEnabled(true);
        fetch.runOne();
        assertThat(logStatus(pPaused)).isEqualTo("READY");
        outbox.poll(); // publishEnabled still false
        assertThat(jdbc.queryForObject("SELECT status FROM ci_failure_event_outbox WHERE pipeline_id=?", String.class, pPaused))
                .isEqualTo("PENDING");
        ciProperties.setPublishEnabled(true);
        consumer.reset();
        outbox.poll();
        assertThat(jdbc.queryForObject("SELECT status FROM ci_failure_event_outbox WHERE pipeline_id=?", String.class, pPaused))
                .isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject("SELECT log_status FROM ci_failures WHERE pipeline_id=?", String.class, legacyPipeline))
                .isEqualTo("LEGACY");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ci_failure_event_outbox WHERE pipeline_id=?", Long.class, legacyPipeline))
                .isZero();

        // EVT-05: listener swallowing/returning async marks bus PUBLISHED without proving downstream AI completion
        UUID pSwallow = seedPendingFailure(REPOSITORY, "3004", 1);
        fetch.runOne();
        consumer.reset();
        consumer.swallowAndSkipCompletion = true;
        outbox.dispatch();
        assertThat(jdbc.queryForObject("SELECT status FROM ci_failure_event_outbox WHERE pipeline_id=?", String.class, pSwallow))
                .isEqualTo("PUBLISHED");
        assertThat(consumer.received).hasSize(1);
        assertThat(consumer.downstreamCompleted.get()).isZero();
    }

    @Test
    void enforcesCrossAttemptReadIsolationDeletionRacesRetentionPendingGuardAndSafeObservability() throws Exception {
        // Attach Logback ListAppender to verify SEC-11 & OPS-03 zero secret/snippet/URL leaks in app logs
        Logger gitciLogger = (Logger) LoggerFactory.getLogger("io.devflow.gitci");
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        gitciLogger.addAppender(appender);
        try {
            // READ-01 / READ-02 / READ-04: cross-attempt mismatch, forged provenance, digest preservation on truncation
            UUID p1 = seedPendingFailure(REPOSITORY, "4001", 1);
            UUID p2 = seedPendingFailure(REPOSITORY, "4001", 2);
            when(github.fetch(any(), anyLong()))
                    .thenReturn(archive("Authorization: Bearer " + SECRET_CANARY + "\n" + SIGNED_URL_CANARY + "\nError: attempt one unique trace"))
                    .thenReturn(archive("Error: attempt two unique trace"));
            fetch.runOne();
            fetch.runOne();
            outbox.dispatch();
            outbox.dispatch();
            var evt1 = consumer.received.get(0);
            var evt2 = consumer.received.get(1);

            // Truncated read keeps original DB digest and sets truncated=true
            String dbDigest = jdbc.queryForObject("SELECT log_digest FROM ci_failures WHERE pipeline_id=?", String.class, p1);
            var smallRead = CiFailureLogReadRequestedEvent.forDetection(UUID.randomUUID(), evt1, 20);
            events.publishEvent(smallRead);
            assertThat(smallRead.response().status()).isEqualTo(Status.READY);
            assertThat(smallRead.response().truncated()).isTrue();
            assertThat(smallRead.response().digest()).isEqualTo(dbDigest);
            assertThat(smallRead.response().excerpt().getBytes(StandardCharsets.UTF_8)).hasSizeLessThanOrEqualTo(20);

            // Cross-attempt mismatch: pipelineId of attempt 1 + rawLogReference of attempt 2 -> NOT_FOUND
            var crossRef = new CiFailureDetectedEvent(evt1.pipelineId(), evt2.rawLogReference(), evt1.repo(), evt1.commitSha(),
                    evt1.occurredAt(), evt1.eventId(), evt1.repositoryId(), evt1.workspaceId(), evt1.providerRunId(), evt1.runAttempt());
            assertThat(readStatus(crossRef)).isEqualTo(Status.NOT_FOUND);
            // Forged eventId / commitSha / repo / occurredAt -> DENIED
            assertThat(readStatus(new CiFailureDetectedEvent(evt1.pipelineId(), evt1.rawLogReference(), evt1.repo(), evt1.commitSha(),
                    evt1.occurredAt(), UUID.randomUUID(), evt1.repositoryId(), evt1.workspaceId(), evt1.providerRunId(), evt1.runAttempt())))
                    .isEqualTo(Status.DENIED);
            assertThat(readStatus(new CiFailureDetectedEvent(evt1.pipelineId(), evt1.rawLogReference(), "other/repo", evt1.commitSha(),
                    evt1.occurredAt(), evt1.eventId(), evt1.repositoryId(), evt1.workspaceId(), evt1.providerRunId(), evt1.runAttempt())))
                    .isEqualTo(Status.DENIED);
            assertThat(readStatus(new CiFailureDetectedEvent(evt1.pipelineId(), evt1.rawLogReference(), evt1.repo(), "d".repeat(40),
                    evt1.occurredAt(), evt1.eventId(), evt1.repositoryId(), evt1.workspaceId(), evt1.providerRunId(), evt1.runAttempt())))
                    .isEqualTo(Status.DENIED);

            // DEL-03: purge must KEEP expired READY snippet while outbox is still PENDING/PROCESSING
            UUID pRetain = seedPendingFailure(REPOSITORY, "4002", 1);
            when(github.fetch(any(), anyLong())).thenReturn(archive("Error: keep while outbox pending"));
            fetch.runOne();
            jdbc.update("UPDATE ci_failures SET log_ready_at=now()-interval '10 days', log_expires_at=now()-interval '1 day' WHERE pipeline_id=?", pRetain);
            outbox.purge();
            assertThat(logStatus(pRetain)).isEqualTo("READY");
            assertThat(jdbc.queryForObject("SELECT raw_log_snippet FROM ci_failures WHERE pipeline_id=?", String.class, pRetain))
                    .contains("keep while outbox pending");
            // Once outbox is marked PUBLISHED, purge expires the snippet cleanly
            jdbc.update("UPDATE ci_failure_event_outbox SET status='PUBLISHED', published_at=now() WHERE pipeline_id=?", pRetain);
            outbox.purge();
            assertThat(logStatus(pRetain)).isEqualTo("EXPIRED");
            assertThat(jdbc.queryForObject("SELECT raw_log_snippet FROM ci_failures WHERE pipeline_id=?", String.class, pRetain))
                    .isNull();

            // DEL-01 via WorkspaceDeletedEvent: cascades PENDING fetch job AND PENDING outbox
            UUID pWsJob = seedPendingFailure(REPOSITORY_B, "5001", 1);
            UUID pWsOutbox = seedPendingFailure(REPOSITORY_B, "5002", 1);
            jdbc.update("UPDATE ci_log_fetch_jobs SET next_attempt_at=now()+interval '1 hour' WHERE pipeline_id=?", pWsJob);
            when(github.fetch(any(), anyLong())).thenReturn(archive("Error: workspace B pending outbox"));
            fetch.runOne();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ci_failure_event_outbox WHERE repository_id=?", Long.class, REPOSITORY_B))
                    .isOne();
            new TransactionTemplate(txManager).executeWithoutResult(status ->
                    events.publishEvent(new WorkspaceDeletedEvent(workspaceB, UUID.randomUUID())));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM repositories WHERE workspace_id=?", Long.class, workspaceB)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ci_log_fetch_jobs WHERE pipeline_id IN (?,?)", Long.class, pWsJob, pWsOutbox)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ci_failure_event_outbox WHERE workspace_id=?", Long.class, workspaceB)).isZero();

            // DEL-02: repo deleted AFTER pre-publish check, BEFORE consumer read -> reader returns NOT_FOUND
            UUID pRace = seedPendingFailure(REPOSITORY, "4003", 1);
            jdbc.update("UPDATE ci_log_fetch_jobs SET next_attempt_at=now()-interval '1 second' WHERE pipeline_id=?", pRace);
            when(github.fetch(any(), anyLong())).thenReturn(archive("Error: delete before read"));
            fetch.runOne();
            consumer.reset();
            consumer.deleteRepoBeforeRead = true;
            outbox.dispatch();
            assertThat(consumer.lastRead.status()).isEqualTo(Status.NOT_FOUND);

            // OPS-03: trigger PERMISSION_DENIED and check /actuator/health stays UP + safe metrics/logs
            jdbc.update("""
                    INSERT INTO repositories(id,workspace_id,provider,remote_repo_id,name,repository_url,webhook_secret_ref)
                    VALUES (?,?,'GITHUB','501','repo-a','https://github.com/example/repo-a','ref:a')
                    """, REPOSITORY, workspace);
            UUID pDenied = seedPendingFailure(REPOSITORY, "4004", 1);
            when(github.fetch(any(), anyLong())).thenThrow(new FetchFailure(Kind.DENIED, "CREDENTIAL_NOT_CONFIGURED"));
            fetch.runOne();
            assertThat(logStatus(pDenied)).isEqualTo("PERMISSION_DENIED");

            var healthResponse = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health"))
                            .timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(healthResponse.statusCode()).isEqualTo(200);
            assertThat(healthResponse.body()).contains("\"status\":\"UP\"");

            // Verify finite low-cardinality Micrometer tags on all devflow.gitci.ci.* meters
            List<Meter> ciMeters = metrics.getMeters().stream()
                    .filter(m -> m.getId().getName().startsWith("devflow.gitci.ci."))
                    .toList();
            assertThat(ciMeters).extracting(m -> m.getId().getName()).contains(
                    "devflow.gitci.ci.fetch.depth",
                    "devflow.gitci.ci.fetch.oldest.seconds",
                    "devflow.gitci.ci.fetch.outcomes",
                    "devflow.gitci.ci.fetch.duration",
                    "devflow.gitci.ci.outbox.depth",
                    "devflow.gitci.ci.outbox.oldest.seconds",
                    "devflow.gitci.ci.outbox.outcomes",
                    "devflow.gitci.ci.log.reads",
                    "devflow.gitci.ci.retention.expired");
            Set<String> allowedTags = Set.of("outcome", "code");
            for (Meter meter : ciMeters) {
                for (var tag : meter.getId().getTags()) {
                    assertThat(allowedTags).contains(tag.getKey());
                    assertThat(tag.getValue()).doesNotContain(SECRET_CANARY, SIGNED_URL_CANARY, SHA, workspace.toString());
                }
            }

            // Verify app logs never leaked secrets, raw snippets, or signed URLs
            for (ILoggingEvent logEvent : appender.list) {
                String msg = logEvent.getFormattedMessage();
                assertThat(msg).doesNotContain(SECRET_CANARY, SIGNED_URL_CANARY, "attempt one unique trace", "secret_sig_999");
            }
        } finally {
            gitciLogger.detachAppender(appender);
            appender.stop();
        }
    }

    private UUID seedPendingFailure(UUID repositoryId, String runId, int attempt) {
        UUID pipelineId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ci_pipelines(id,repository_id,pipeline_id,run_attempt,commit_sha,branch,status,
                    provider_status,provider_conclusion,started_at,finished_at,provider_updated_at)
                VALUES (?,?,?,?,?,'main','FAILED','completed','failure',now()-interval '10 seconds',now(),now())
                """, pipelineId, repositoryId, runId, attempt, SHA);
        UUID failureId = jdbc.queryForObject("""
                INSERT INTO ci_failures(pipeline_id,failure_key,stage_name,job_name,log_status)
                VALUES (?,'workflow-run','workflow','aggregate','PENDING') RETURNING id
                """, UUID.class, pipelineId);
        jdbc.update("INSERT INTO ci_log_fetch_jobs(pipeline_id,failure_id) VALUES (?,?)", pipelineId, failureId);
        return pipelineId;
    }

    private void makeJobDue(UUID pipelineId) {
        jdbc.update("UPDATE ci_log_fetch_jobs SET next_attempt_at=now()-interval '1 second' WHERE pipeline_id=?", pipelineId);
    }

    private String logStatus(UUID pipelineId) {
        return jdbc.queryForObject("SELECT log_status FROM ci_failures WHERE pipeline_id=?", String.class, pipelineId);
    }

    private java.util.Map<String, Object> jobMap(UUID pipelineId) {
        return jdbc.queryForMap("SELECT status,attempts,not_found_attempts,error_code FROM ci_log_fetch_jobs WHERE pipeline_id=?", pipelineId);
    }

    private Status readStatus(CiFailureDetectedEvent event) {
        var request = CiFailureLogReadRequestedEvent.forDetection(UUID.randomUUID(), event, 1024);
        events.publishEvent(request);
        return request.response().status();
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private static GitHubActionsReadClient.Archive archive(String content) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("0_build.txt"));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return new GitHubActionsReadClient.Archive(bytes.toByteArray(),
                List.of(new GitHubActionsReadClient.Job(1L, "build", true, List.of())), false);
    }
}
