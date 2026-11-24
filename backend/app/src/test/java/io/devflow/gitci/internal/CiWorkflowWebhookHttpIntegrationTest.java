package io.devflow.gitci.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.devflow.common.event.CiFailureDetectedEvent;
import io.devflow.common.event.CiFailureLogReadRequestedEvent;
import io.devflow.common.event.CiFailureLogReadRequestedEvent.Status;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CiWorkflowWebhookHttpIntegrationTest {
    private static final UUID REPOSITORY = UUID.fromString("ae566c4f-e4ea-4300-9c8b-785095fcd458");
    private static final UUID REPOSITORY_B = UUID.fromString("bf677d50-f5fb-4411-ad9c-896106fde569");
    private static final String KEY = "t025-fixture-do-not-use-000011112222";
    private static final String PREV_VALID_KEY = "t025-prev-valid-do-not-use-333344445555";
    private static final String KEY_B = "t025-repo-b-do-not-use-666677778888";
    private static final String PREV_EXPIRED_KEY = "t025-prev-expired-do-not-use-999900001111";
    private static final String SHA = "b".repeat(40);
    private static final String TOKEN = "github_pat_canary_is_never_persisted";
    private static final String UPDATED = "2026-11-10T10:00:10Z";
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17-alpine");
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationEventPublisher events;
    @Autowired CiLogFetchWorker fetch;
    @Autowired CiFailureEventOutbox outbox;
    @Autowired GitHubActionsProperties ciProperties;
    @Autowired io.micrometer.core.instrument.MeterRegistry metrics;
    @Autowired CapturedFailures consumer;
    @MockitoBean GitHubActionsReadClient github;
    private final HttpClient client = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();
    private UUID workspace;
    private UUID workspaceB;

    @DynamicPropertySource static void config(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DB::getJdbcUrl);
        properties.add("spring.datasource.username", DB::getUsername);
        properties.add("spring.datasource.password", DB::getPassword);
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("spring.flyway.enabled", () -> "true");
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        properties.add("devflow.gitci.github-webhook.enabled", () -> "true");
        properties.add("devflow.gitci.github-webhook.max-body-bytes", () -> "4096");
        properties.add("devflow.gitci.github-webhook.bindings[" + REPOSITORY + "].reference", () -> "t025:test");
        properties.add("devflow.gitci.github-webhook.bindings[" + REPOSITORY + "].current", () -> KEY);
        properties.add("devflow.gitci.github-webhook.bindings[" + REPOSITORY + "].previous", () -> PREV_VALID_KEY);
        properties.add("devflow.gitci.github-webhook.bindings[" + REPOSITORY + "].previous-valid-until", () -> "2030-01-01T00:00:00Z");
        properties.add("devflow.gitci.github-webhook.bindings[" + REPOSITORY_B + "].reference", () -> "t025:test-b");
        properties.add("devflow.gitci.github-webhook.bindings[" + REPOSITORY_B + "].current", () -> KEY_B);
        properties.add("devflow.gitci.github-webhook.bindings[" + REPOSITORY_B + "].previous", () -> PREV_EXPIRED_KEY);
        properties.add("devflow.gitci.github-webhook.bindings[" + REPOSITORY_B + "].previous-valid-until", () -> "2020-01-01T00:00:00Z");
    }

    @TestConfiguration static class Config {
        @Bean CapturedFailures failures(ApplicationEventPublisher events, JdbcTemplate jdbc) { return new CapturedFailures(events, jdbc); }
    }

    static final class CapturedFailures {
        final ApplicationEventPublisher events;
        final JdbcTemplate jdbc;
        final java.util.ArrayList<CiFailureDetectedEvent> delivered = new java.util.ArrayList<>();
        CiFailureLogReadRequestedEvent.Response log;
        boolean failOnce;
        boolean record = true;

        CapturedFailures(ApplicationEventPublisher events, JdbcTemplate jdbc) { this.events = events; this.jdbc = jdbc; }

        @EventListener void consume(CiFailureDetectedEvent event) {
            if (!record) return;
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(jdbc.queryForObject("SELECT log_status FROM ci_failures WHERE pipeline_id=?",
                    String.class, UUID.fromString(event.pipelineId()))).isEqualTo("READY");
            delivered.add(event);
            if (failOnce) {
                failOnce = false;
                throw new IllegalStateException("synthetic consumer error");
            }
            var request = CiFailureLogReadRequestedEvent.forDetection(UUID.randomUUID(), event, 16_384);
            events.publishEvent(request);
            assertThat(request.responseCount()).isEqualTo(1);
            log = request.response();
        }
    }

    @BeforeEach void fixtures() {
        jdbc.update("DELETE FROM repositories");
        workspace = UUID.randomUUID();
        workspaceB = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO repositories(id,workspace_id,provider,remote_repo_id,name,repository_url,webhook_secret_ref)
                VALUES (?,?,'GITHUB','123','fixture','https://github.com/example/repo','t025:test'),
                       (?,?,'GITHUB','456','fixture-b','https://github.com/example/repo-b','t025:test-b')
                """, REPOSITORY, workspace, REPOSITORY_B, workspaceB);
        consumer.delivered.clear();
        consumer.log = null;
        consumer.failOnce = false;
        consumer.record = true;
    }

    @Test void recordsFailureBeforePushThenPublishesSameEventAfterRetryAndReadsSanitizedLog() throws Exception {
        ObjectNode payload = workflow(1, "failure");
        payload.withObject("/workflow_run").putNull("head_branch");
        String delivery = UUID.randomUUID().toString();
        assertThat(send(payload, delivery)).isEqualTo(204);
        assertThat(send(payload, delivery)).isEqualTo(204);
        assertThat(send(payload, UUID.randomUUID().toString())).isEqualTo(204);
        payload.put("ignored", true);
        jdbc.update("DELETE FROM github_webhook_receipts");
        assertThat(send(payload, UUID.randomUUID().toString())).isEqualTo(204);
        assertThat(jdbc.queryForMap("SELECT status,branch,run_attempt,commit_sha FROM ci_pipelines"))
                .containsEntry("status", "FAILED").containsEntry("branch", null).containsEntry("run_attempt", 1).containsEntry("commit_sha", SHA);
        assertThat(jdbc.queryForMap("SELECT log_status, related_task_id FROM ci_failures"))
                .containsEntry("log_status", "PENDING").containsEntry("related_task_id", null);
        assertThat(count("git_commits")).isZero();
        assertThat(count("ci_log_fetch_jobs")).isEqualTo(1);
        assertThat(count("ci_failure_event_outbox")).isZero();
        verifyNoInteractions(github);
        var ready = archive("Authorization: Bearer " + TOKEN + "\n##[error] Build failed: assertion mismatch\ndetails\n");
        when(github.fetch(any(), anyLong())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(call.<GitHubActionsReadClient.Target>getArgument(0))
                    .isEqualTo(new GitHubActionsReadClient.Target(REPOSITORY, "123", "https://github.com/example/repo", "777", 1, SHA));
            return ready;
        });
        fetch.runOne();
        assertThat(count("ci_failure_event_outbox")).isEqualTo(1);
        assertThat(consumer.delivered).isEmpty();
        assertThat(jdbc.queryForObject("SELECT raw_log_snippet FROM ci_failures", String.class))
                .contains("assertion mismatch").doesNotContain(TOKEN);
        consumer.failOnce = true;
        outbox.dispatch();
        jdbc.update("UPDATE ci_failure_event_outbox SET next_attempt_at=CURRENT_TIMESTAMP");
        outbox.dispatch();
        assertThat(consumer.delivered).hasSize(2);
        var event = consumer.delivered.get(0);
        assertThat(consumer.delivered.get(1)).isEqualTo(event);
        assertThat(event.repositoryId()).isEqualTo(REPOSITORY);
        assertThat(event.workspaceId()).isEqualTo(workspace);
        assertThat(event.providerRunId()).isEqualTo("777");
        assertThat(event.runAttempt()).isEqualTo(1);
        assertThat(event.commitSha()).isEqualTo(SHA);
        assertThat(event.rawLogReference()).startsWith("ci-log:");
        assertThat(consumer.log.status()).isEqualTo(Status.READY);
        assertThat(consumer.log.excerpt()).contains("assertion mismatch").doesNotContain(TOKEN);
        assertThat(jdbc.queryForObject("SELECT status FROM ci_failure_event_outbox", String.class)).isEqualTo("PUBLISHED");

        var legacy = new CiFailureDetectedEvent(event.pipelineId(), event.rawLogReference(), event.repo(), SHA);
        assertThat(read(legacy)).isEqualTo(Status.DENIED);
        var wrongAttempt = new CiFailureDetectedEvent(event.pipelineId(), event.rawLogReference(), event.repo(), SHA,
                event.occurredAt(), event.eventId(), REPOSITORY, workspace, "777", 2);
        assertThat(read(wrongAttempt)).isEqualTo(Status.DENIED);
        var wrongTenant = new CiFailureDetectedEvent(event.pipelineId(), event.rawLogReference(), event.repo(), SHA,
                event.occurredAt(), event.eventId(), REPOSITORY, UUID.randomUUID(), "777", 1);
        assertThat(read(wrongTenant)).isEqualTo(Status.DENIED);

        assertThat(send(workflow(2, "success"), UUID.randomUUID().toString())).isEqualTo(204);
        assertThat(count("ci_pipelines")).isEqualTo(2);
        assertThat(count("ci_failures")).isEqualTo(1);
        jdbc.update("UPDATE ci_failures SET log_ready_at=now()-interval '2 days',log_expires_at=now()-interval '1 day'");
        assertThat(read(event)).isEqualTo(Status.EXPIRED);
        jdbc.update("UPDATE ci_failure_event_outbox SET published_at=now()-interval '8 days'");
        outbox.purge();
        assertThat(count("ci_failure_event_outbox")).isZero();
        assertThat(jdbc.queryForObject("SELECT raw_log_snippet FROM ci_failures", String.class)).isNull();
        assertThat(read(event)).isEqualTo(Status.EXPIRED);
        assertThat(send(payload.put("ignored", false), UUID.randomUUID().toString())).isEqualTo(204);
        assertThat(count("ci_failure_event_outbox")).isZero();
        jdbc.update("DELETE FROM repositories");
        assertThat(read(event)).isEqualTo(Status.NOT_FOUND);
        assertThat(count("ci_log_fetch_jobs")).isZero();
    }

    @ParameterizedTest
    @CsvSource({"success,SUCCESS", "cancelled,CANCELLED", "skipped,SKIPPED", "timed_out,TIMED_OUT",
            "action_required,ACTION_REQUIRED", "neutral,NEUTRAL", "stale,STALE", "new_conclusion,UNKNOWN"})
    void storesNonfailureWithoutJob(String conclusion, String state) throws Exception {
        assertThat(send(workflow(1, conclusion), UUID.randomUUID().toString())).isEqualTo(204);
        assertThat(jdbc.queryForMap("SELECT status,provider_conclusion FROM ci_pipelines"))
                .containsEntry("status", state).containsEntry("provider_conclusion", conclusion);
        assertThat(count("ci_failures")).isZero();
        assertThat(count("ci_log_fetch_jobs")).isZero();
        assertThat(count("ci_failure_event_outbox")).isZero();
    }

    @Test void actionValidationOrderingAndTerminalConflict() throws Exception {
        ObjectNode requested = workflow(1, "failure").put("action", "requested");
        assertThat(send(requested, UUID.randomUUID().toString())).isEqualTo(204);
        ObjectNode unknown = workflow(1, "failure").put("action", "new_action");
        assertThat(send(unknown, UUID.randomUUID().toString())).isEqualTo(204);
        assertThat(count("ci_pipelines")).isZero();
        ObjectNode invalid = workflow(1, "failure");
        invalid.withObject("/workflow_run").remove("run_attempt");
        assertThat(send(invalid, UUID.randomUUID().toString())).isEqualTo(400);
        invalid.withObject("/workflow_run").put("run_attempt", "1");
        assertThat(send(invalid, UUID.randomUUID().toString())).isEqualTo(400);
        assertThat(send(workflow(1, "failure"), UUID.randomUUID().toString())).isEqualTo(204);
        ObjectNode stale = workflow(1, "failure");
        stale.withObject("/workflow_run").put("updated_at", "2026-11-10T10:00:09Z").put("head_branch", "stale");
        assertThat(send(stale, UUID.randomUUID().toString())).isEqualTo(204);
        stale.withObject("/workflow_run").put("updated_at", UPDATED);
        assertThat(send(stale, UUID.randomUUID().toString())).isEqualTo(409);
        assertThat(send(workflow(1, "success"), UUID.randomUUID().toString())).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT branch FROM ci_pipelines", String.class)).isEqualTo("main");
        assertThat(count("ci_log_fetch_jobs")).isOne();
    }

    @Test void rollbackAndRedeliveryKeepReceiptAndJobAtomic() throws Exception {
        jdbc.execute("""
                CREATE FUNCTION ci_test_reject() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'synthetic write failure'; END $$;
                CREATE TRIGGER ci_test_reject BEFORE INSERT ON ci_log_fetch_jobs FOR EACH ROW EXECUTE FUNCTION ci_test_reject()
                """);
        var delivery = UUID.randomUUID().toString();
        try {
            assertThat(send(workflow(1, "failure"), delivery)).isEqualTo(503);
            assertThat(count("ci_pipelines")).isZero();
            assertThat(count("ci_failures")).isZero();
            assertThat(count("github_webhook_receipts")).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER ci_test_reject ON ci_log_fetch_jobs; DROP FUNCTION ci_test_reject()");
        }
        assertThat(send(workflow(1, "failure"), delivery)).isEqualTo(204);
        assertThat(count("ci_failures")).isOne();
        assertThat(count("ci_log_fetch_jobs")).isOne();
    }

    @Test void pausedPublisherStillCountsReadyEventsAgainstTheBacklog() throws Exception {
        int initial = ciProperties.getRepositoryBacklog();
        ciProperties.setRepositoryBacklog(1);
        try {
            assertThat(send(workflow(1, "failure"), UUID.randomUUID().toString())).isEqualTo(204);
            when(github.fetch(any(), anyLong())).thenReturn(archive("Error: pending publisher"));
            fetch.runOne();
            assertThat(send(workflow(2, "failure"), UUID.randomUUID().toString())).isEqualTo(503);
            assertThat(count("ci_pipelines")).isOne();
            assertThat(count("ci_failure_event_outbox")).isOne();
            assertThat(count("github_webhook_receipts")).isOne();
            outbox.dispatch();
            assertThat(send(workflow(2, "failure"), UUID.randomUUID().toString())).isEqualTo(204);
            assertThat(count("ci_pipelines")).isEqualTo(2);
        } finally { ciProperties.setRepositoryBacklog(initial); }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "DEVFLOW_T025_LOAD_TEST", matches = "true")
    void sustainsOneHundredCompletedFailuresPerMinuteForTenMinutesWithSlowGitHub() throws Exception {
        consumer.record = false;
        var source = archive("Error: synthetic load-test assertion");
        when(github.fetch(any(), anyLong())).thenAnswer(call -> {
            TimeUnit.MILLISECONDS.sleep(700);
            return source;
        });
        var active = new java.util.concurrent.atomic.AtomicBoolean(true);
        var pool = Executors.newFixedThreadPool(3);
        Runnable fetchLoop = () -> { while (active.get()) { fetch.runOne(); waitForNextPoll(); } };
        var fetchOne = pool.submit(fetchLoop);
        var fetchTwo = pool.submit(fetchLoop);
        var publish = pool.submit(() -> { while (active.get()) { outbox.dispatch(); waitForNextPoll(); } });
        try {
            var latencies = new long[1000];
            long start = System.nanoTime();
            for (int i = 0; i < latencies.length; i++) {
                long remaining = start + TimeUnit.MILLISECONDS.toNanos(600L * i) - System.nanoTime();
                if (remaining > 0) TimeUnit.NANOSECONDS.sleep(remaining);
                var payload = workflow(1, "failure");
                payload.withObject("/workflow_run").put("id", 1000L + i);
                long sent = System.nanoTime();
                assertThat(send(payload, UUID.randomUUID().toString())).as("delivery %s", i).isEqualTo(204);
                latencies[i] = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sent);
            }
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                    assertThat(jdbc.queryForObject("SELECT count(*) FROM ci_failure_event_outbox WHERE status='PUBLISHED'", Long.class))
                            .isEqualTo(1000));
            java.util.Arrays.sort(latencies);
            long p95 = latencies[949];
            System.out.printf("T-025 OPS-01: 1000 deliveries, 700ms stub, ACK p95=%d ms%n", p95);
            assertThat(p95).isLessThanOrEqualTo(1000);
            assertThat(count("ci_failures")).isEqualTo(1000);
            assertThat(count("github_webhook_receipts")).isEqualTo(1000);
            assertThat(count("ci_failure_event_outbox")).isEqualTo(1000);
        } finally {
            active.set(false);
            try {
                fetchOne.get(6, TimeUnit.SECONDS);
                fetchTwo.get(6, TimeUnit.SECONDS);
                publish.get(6, TimeUnit.SECONDS);
            } finally { pool.shutdownNow(); }
        }
    }

    private static void waitForNextPoll() {
        try { Thread.sleep(100); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    }

    @Test void expiredClaimCannotOverwriteItsReplacement() throws Exception {
        assertThat(send(workflow(1, "failure"), UUID.randomUUID().toString())).isEqualTo(204);
        var delayed = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var first = archive("Error: stale context");
        var second = archive("Error: current context");
        when(github.fetch(any(), anyLong())).thenAnswer(call -> {
            delayed.countDown();
            if (!resume.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("synthetic latch timed out");
            return first;
        }).thenReturn(second);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var old = pool.submit(fetch::runOne);
            assertThat(delayed.await(8, TimeUnit.SECONDS)).isTrue();
            jdbc.update("UPDATE ci_log_fetch_jobs SET lease_until=CURRENT_TIMESTAMP - interval '1 second'");
            fetch.runOne();
            resume.countDown();
            old.get(8, TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("SELECT raw_log_snippet FROM ci_failures", String.class))
                    .contains("current context").doesNotContain("stale context");
            assertThat(count("ci_failure_event_outbox")).isOne();
        } finally { resume.countDown(); pool.shutdownNow(); }
    }

    @Test void memberLookupUsesCurrentAuthMembershipAndFailsWithMultipleResponders() throws Exception {
        var member = UUID.randomUUID();
        var outsider = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,email,password_hash,full_name,status) VALUES (?,?,'fixture','Test Member','ACTIVE')",
                member, member + "@example.invalid");
        jdbc.update("INSERT INTO users(id,email,password_hash,full_name,status) VALUES (?,?,'fixture','Test Outsider','ACTIVE')",
                outsider, outsider + "@example.invalid");
        jdbc.update("INSERT INTO workspaces(id,name,slug,owner_id) VALUES (?,'Fixture',?,?)", workspace, workspace.toString(), member);
        jdbc.update("INSERT INTO workspace_members(workspace_id,user_id,role) VALUES (?,?,'MEMBER')", workspace, member);
        assertThat(send(workflow(1, "failure"), UUID.randomUUID().toString())).isEqualTo(204);
        UUID pipeline = jdbc.queryForObject("SELECT id FROM ci_pipelines", UUID.class);
        String reference = "ci-log:" + jdbc.queryForObject("SELECT id FROM ci_failures", UUID.class);
        assertThat(memberRead(pipeline, reference, member).status()).isEqualTo(Status.NOT_READY);
        when(github.fetch(any(), anyLong())).thenReturn(archive("Error: synthetic CI assertion failed"));
        fetch.runOne();
        var request = CiFailureLogReadRequestedEvent.forWorkspaceMember(UUID.randomUUID(), pipeline, reference, workspace, member, 14);
        events.publishEvent(request);
        assertThat(request.response().status()).isEqualTo(Status.READY);
        assertThat(request.response().truncated()).isTrue();
        assertThat(request.response().excerpt().getBytes(StandardCharsets.UTF_8)).hasSizeLessThanOrEqualTo(14);
        request.respond(CiFailureLogReadRequestedEvent.Response.status(Status.NOT_READY));
        assertThat(request.response().status()).isEqualTo(Status.DENIED);
        assertThat(memberRead(pipeline, reference, outsider).status()).isEqualTo(Status.DENIED);
        jdbc.update("DELETE FROM workspace_members WHERE workspace_id=? AND user_id=?", workspace, member);
        assertThat(memberRead(pipeline, reference, member).status()).isEqualTo(Status.DENIED);
    }

    @Test void concurrentDeliveriesAndDeletionDuringFetchDoNotDuplicateOrRestoreData() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var begin = new CountDownLatch(1);
        var fetching = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        try {
            var first = pool.submit(() -> { begin.await(); return send(workflow(1, "failure"), UUID.randomUUID().toString()); });
            var second = pool.submit(() -> { begin.await(); return send(workflow(1, "failure").put("other", true), UUID.randomUUID().toString()); });
            begin.countDown();
            assertThat(first.get(8, TimeUnit.SECONDS)).isEqualTo(204);
            assertThat(second.get(8, TimeUnit.SECONDS)).isEqualTo(204);
            assertThat(count("ci_failures")).isOne();
            assertThat(count("ci_log_fetch_jobs")).isOne();
            when(github.fetch(any(), anyLong())).thenAnswer(call -> {
                fetching.countDown();
                if (!resume.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("synthetic latch timed out");
                return archive("Error: deleted repository");
            });
            var work = pool.submit(fetch::runOne);
            assertThat(fetching.await(8, TimeUnit.SECONDS)).isTrue();
            jdbc.update("DELETE FROM repositories");
            resume.countDown();
            work.get(8, TimeUnit.SECONDS);
            assertThat(count("ci_pipelines")).isZero();
            assertThat(count("ci_failures")).isZero();
            assertThat(count("ci_log_fetch_jobs")).isZero();
            assertThat(count("ci_failure_event_outbox")).isZero();
        } finally { resume.countDown(); pool.shutdownNow(); }
    }

    @Test void permissionFailureIsDurableAndReadyWriteRollsBackWithTheOutbox() throws Exception {
        assertThat(send(workflow(1, "failure"), UUID.randomUUID().toString())).isEqualTo(204);
        when(github.fetch(any(), anyLong())).thenThrow(new GitHubActionsReadClient.FetchFailure(
                GitHubActionsReadClient.Kind.DENIED, "CREDENTIAL_NOT_CONFIGURED")).thenReturn(archive("Error: recovered"));
        fetch.runOne();
        assertThat(jdbc.queryForObject("SELECT log_status FROM ci_failures", String.class)).isEqualTo("PERMISSION_DENIED");
        assertThat(count("ci_failure_event_outbox")).isZero();
        jdbc.update("UPDATE ci_log_fetch_jobs SET status='PENDING',next_attempt_at=CURRENT_TIMESTAMP,attempts=0");
        jdbc.execute("""
                CREATE FUNCTION ci_test_reject() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'synthetic outbox failure'; END $$;
                CREATE TRIGGER ci_test_reject BEFORE INSERT ON ci_failure_event_outbox FOR EACH ROW EXECUTE FUNCTION ci_test_reject()
                """);
        try {
            assertThatThrownBy(fetch::runOne).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(jdbc.queryForObject("SELECT log_status FROM ci_failures", String.class)).isEqualTo("FETCHING");
            assertThat(jdbc.queryForObject("SELECT raw_log_snippet FROM ci_failures", String.class)).isNull();
            assertThat(jdbc.queryForObject("SELECT failure_event_enqueued FROM ci_pipelines", Boolean.class)).isFalse();
        } finally {
            jdbc.execute("DROP TRIGGER ci_test_reject ON ci_failure_event_outbox; DROP FUNCTION ci_test_reject()");
        }
        jdbc.update("UPDATE ci_log_fetch_jobs SET lease_until=CURRENT_TIMESTAMP - interval '1 second'");
        fetch.runOne();
        assertThat(jdbc.queryForObject("SELECT log_status FROM ci_failures", String.class)).isEqualTo("READY");
        assertThat(count("ci_failure_event_outbox")).isOne();
    }

    @Test void outOfOrderFailedAttemptsAndMultiWorkspaceSameRunIdStayIsolated() throws Exception {
        // WH-10: attempt 2 arrives before attempt 1; both failures/events stay distinct with attempt-specific logs
        var attempt2 = workflow(2, "failure");
        attempt2.withObject("/workflow_run").put("head_sha", "e".repeat(40));
        assertThat(send(attempt2, UUID.randomUUID().toString())).isEqualTo(204);
        assertThat(send(workflow(1, "failure"), UUID.randomUUID().toString())).isEqualTo(204);
        // WH-17: same provider run ID 777 and attempt 1 in repo B (workspace B)
        var repoBPayload = workflowFor(456, 777, 1, "failure");
        assertThat(sendTo(REPOSITORY_B, KEY_B, repoBPayload, UUID.randomUUID().toString())).isEqualTo(204);

        assertThat(count("ci_pipelines")).isEqualTo(3);
        assertThat(count("ci_failures")).isEqualTo(3);
        assertThat(count("ci_log_fetch_jobs")).isEqualTo(3);

        when(github.fetch(any(), anyLong())).thenAnswer(call -> {
            GitHubActionsReadClient.Target target = call.getArgument(0);
            return archive("Error: repo=" + target.remoteId() + " attempt=" + target.attempt());
        });
        fetch.runOne();
        fetch.runOne();
        fetch.runOne();
        outbox.dispatch();
        outbox.dispatch();
        outbox.dispatch();

        assertThat(consumer.delivered).hasSize(3);
        assertThat(consumer.delivered).extracting(CiFailureDetectedEvent::eventId).doesNotHaveDuplicates();
        var repoAEvents = consumer.delivered.stream().filter(e -> e.repositoryId().equals(REPOSITORY)).toList();
        var repoBEvents = consumer.delivered.stream().filter(e -> e.repositoryId().equals(REPOSITORY_B)).toList();
        assertThat(repoAEvents).hasSize(2);
        assertThat(repoBEvents).hasSize(1);
        assertThat(repoBEvents.get(0).workspaceId()).isEqualTo(workspaceB);

        // Cross-workspace read using repo B event against workspace A fails closed
        var forgedCrossTenant = new CiFailureDetectedEvent(
                repoBEvents.get(0).pipelineId(), repoBEvents.get(0).rawLogReference(), repoBEvents.get(0).repo(),
                repoBEvents.get(0).commitSha(), repoBEvents.get(0).occurredAt(), repoBEvents.get(0).eventId(),
                REPOSITORY_B, workspace, "777", 1);
        assertThat(read(forgedCrossTenant)).isEqualTo(Status.DENIED);
    }

    @Test void enforcesMetricsBranchBoundsDeliveryDigestConflictNewerMetadataAndTerminalConflicts() throws Exception {
        // WH-04: ignored metric increments for non-completed action
        double ignoredBefore = metrics.counter("devflow.gitci.ci.webhook", "outcome", "ignored").count();
        assertThat(send(workflow(1, "failure").put("action", "in_progress"), UUID.randomUUID().toString())).isEqualTo(204);
        assertThat(metrics.counter("devflow.gitci.ci.webhook", "outcome", "ignored").count()).isEqualTo(ignoredBefore + 1.0);

        // WH-08: blank and 256-char branches rejected; 255-char branch accepted
        var blankBranch = workflow(1, "failure");
        blankBranch.withObject("/workflow_run").put("head_branch", "   ");
        assertThat(send(blankBranch, UUID.randomUUID().toString())).isEqualTo(400);
        var tooLongBranch = workflow(1, "failure");
        tooLongBranch.withObject("/workflow_run").put("head_branch", "x".repeat(256));
        assertThat(send(tooLongBranch, UUID.randomUUID().toString())).isEqualTo(400);
        var maxBranch = workflow(1, "failure");
        maxBranch.withObject("/workflow_run").put("head_branch", "x".repeat(255));
        String reusedDelivery = UUID.randomUUID().toString();
        assertThat(send(maxBranch, reusedDelivery)).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT length(branch) FROM ci_pipelines", Integer.class)).isEqualTo(255);

        // WH-13: same delivery ID with different payload digest -> 409 WEBHOOK_DELIVERY_CONFLICT
        var differentDigest = workflow(1, "failure").put("extra", "changed");
        var conflictDelivery = sendRaw(REPOSITORY, KEY, json.writeValueAsBytes(differentDigest), reusedDelivery, "workflow_run", "application/json", null);
        assertThat(conflictDelivery.statusCode()).isEqualTo(409);
        assertThat(conflictDelivery.body()).contains("WEBHOOK_DELIVERY_CONFLICT");

        // WH-14: newer timestamp with same terminal FAILED updates metadata without adding a failure/job
        var newerSameResult = workflow(1, "failure");
        newerSameResult.withObject("/workflow_run").put("updated_at", "2026-11-10T10:00:20Z").put("head_branch", "release");
        assertThat(send(newerSameResult, UUID.randomUUID().toString())).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT branch FROM ci_pipelines WHERE run_attempt=1", String.class)).isEqualTo("release");
        assertThat(count("ci_failures")).isOne();
        assertThat(count("ci_log_fetch_jobs")).isOne();

        // WH-15: newer timestamp with DIFFERENT terminal result (FAILED -> SUCCESS and SUCCESS -> FAILED) -> 409 + conflict metric
        double conflictBefore = metrics.counter("devflow.gitci.ci.webhook", "outcome", "conflict").count();
        var flipToSuccess = workflow(1, "success");
        flipToSuccess.withObject("/workflow_run").put("updated_at", "2026-11-10T10:00:30Z");
        assertThat(send(flipToSuccess, UUID.randomUUID().toString())).isEqualTo(409);

        assertThat(send(workflow(2, "success"), UUID.randomUUID().toString())).isEqualTo(204);
        var flipToFailure = workflow(2, "failure");
        flipToFailure.withObject("/workflow_run").put("updated_at", "2026-11-10T10:00:30Z");
        assertThat(send(flipToFailure, UUID.randomUUID().toString())).isEqualTo(409);
        assertThat(metrics.counter("devflow.gitci.ci.webhook", "outcome", "conflict").count()).isEqualTo(conflictBefore + 2.0);
        assertThat(count("ci_failures")).isOne();
    }

    @Test void enforcesHmacRotationForkHeadRepositoryStrictJsonMediaLimitsAndSafeProblems() throws Exception {
        byte[] validBody = json.writeValueAsBytes(workflow(1, "failure"));

        // SEC-01: missing/wrong signature & tampered body -> 401, 0 writes, 0 GitHub calls
        var missingSig = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/gitci/webhook/github?repositoryId=" + REPOSITORY))
                .timeout(Duration.ofSeconds(8)).header("Content-Type", "application/json")
                .header("X-GitHub-Event", "workflow_run").header("X-GitHub-Delivery", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofByteArray(validBody)).build();
        assertThat(client.send(missingSig, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        assertThat(sendRaw(REPOSITORY, KEY_B, validBody, UUID.randomUUID().toString(), "workflow_run", "application/json", null).statusCode())
                .isEqualTo(401);
        assertThat(count("ci_pipelines")).isZero();
        verifyNoInteractions(github);

        // SEC-02: valid previous key accepted (204), expired previous key rejected (401)
        var rotatedPayload = workflow(1, "failure");
        // SEC-03: fork head_repository accepted while target repository stays bound repo 123
        rotatedPayload.withObject("/workflow_run").putObject("head_repository").put("id", 999999);
        assertThat(sendRaw(REPOSITORY, PREV_VALID_KEY, json.writeValueAsBytes(rotatedPayload),
                UUID.randomUUID().toString(), "workflow_run", "application/json", null).statusCode()).isEqualTo(204);
        assertThat(sendRaw(REPOSITORY_B, PREV_EXPIRED_KEY, json.writeValueAsBytes(workflowFor(456, 777, 1, "failure")),
                UUID.randomUUID().toString(), "workflow_run", "application/json", null).statusCode()).isEqualTo(401);

        // SEC-03: mismatched top-level repository.id and workflow_run.repository.id -> 401
        var wrongTopRepo = workflow(2, "failure");
        wrongTopRepo.withObject("/repository").put("id", 999);
        assertThat(send(wrongTopRepo, UUID.randomUUID().toString())).isEqualTo(401);
        var wrongRunRepo = workflow(2, "failure");
        wrongRunRepo.withObject("/workflow_run").putObject("repository").put("id", 999);
        assertThat(send(wrongRunRepo, UUID.randomUUID().toString())).isEqualTo(401);

        // SEC-04: duplicate JSON key, trailing JSON tokens, invalid UTF-8 -> 400
        String duplicateJson = "{\"action\":\"completed\",\"action\":\"completed\",\"repository\":{\"id\":123},\"workflow_run\":{}}";
        assertThat(sendRaw(REPOSITORY, KEY, duplicateJson.getBytes(StandardCharsets.UTF_8),
                UUID.randomUUID().toString(), "workflow_run", "application/json", null).statusCode()).isEqualTo(400);
        String trailingJson = "{\"action\":\"completed\",\"repository\":{\"id\":123},\"workflow_run\":{}} trailing";
        assertThat(sendRaw(REPOSITORY, KEY, trailingJson.getBytes(StandardCharsets.UTF_8),
                UUID.randomUUID().toString(), "workflow_run", "application/json", null).statusCode()).isEqualTo(400);
        byte[] badUtf8 = new byte[] {'{', '"', 'a', '"', ':', '"', (byte) 0xFF, '"', '}'};
        assertThat(sendRaw(REPOSITORY, KEY, badUtf8, UUID.randomUUID().toString(), "workflow_run", "application/json", null).statusCode())
                .isEqualTo(400);

        // SEC-05: body exceeding configured max-body-bytes (4096) -> 413; wrong media / gzip -> 415
        byte[] oversized = ("{\"pad\":\"" + "a".repeat(4200) + "\"}").getBytes(StandardCharsets.UTF_8);
        assertThat(sendRaw(REPOSITORY, KEY, oversized, UUID.randomUUID().toString(), "workflow_run", "application/json", null).statusCode())
                .isEqualTo(413);
        assertThat(sendRaw(REPOSITORY, KEY, validBody, UUID.randomUUID().toString(), "workflow_run", "text/plain", null).statusCode())
                .isEqualTo(415);
        assertThat(sendRaw(REPOSITORY, KEY, validBody, UUID.randomUUID().toString(), "workflow_run", "application/json", "gzip").statusCode())
                .isEqualTo(415);

        // SEC-06: invalid repositoryId selector -> 400
        var badSelector = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/gitci/webhook/github?repositoryId=not-a-uuid"))
                .timeout(Duration.ofSeconds(8)).header("Content-Type", "application/json")
                .header("X-GitHub-Event", "workflow_run").header("X-GitHub-Delivery", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofByteArray(validBody)).build();
        assertThat(client.send(badSelector, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);

        // SEC-07 & SEC-08: global backlog full returns 503 ProblemDetail with Retry-After and zero SQL/key leaks
        int origGlobal = ciProperties.getGlobalBacklog();
        ciProperties.setGlobalBacklog(1);
        try {
            var fullResp = sendRaw(REPOSITORY_B, KEY_B, json.writeValueAsBytes(workflowFor(456, 888, 1, "failure")),
                    UUID.randomUUID().toString(), "workflow_run", "application/json", null);
            assertThat(fullResp.statusCode()).isEqualTo(503);
            assertThat(fullResp.headers().firstValue("Retry-After")).contains("5");
            assertThat(fullResp.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
            assertThat(fullResp.body()).contains("CI_LOG_BACKLOG_FULL").contains("correlationId")
                    .doesNotContain("SELECT", "SQL", KEY_B, PREV_VALID_KEY, "Exception");
        } finally {
            ciProperties.setGlobalBacklog(origGlobal);
        }
    }

    private Status read(CiFailureDetectedEvent event) {
        var request = CiFailureLogReadRequestedEvent.forDetection(UUID.randomUUID(), event, 1024);
        events.publishEvent(request);
        return request.response().status();
    }

    private CiFailureLogReadRequestedEvent.Response memberRead(UUID pipeline, String reference, UUID user) {
        var request = CiFailureLogReadRequestedEvent.forWorkspaceMember(UUID.randomUUID(), pipeline, reference, workspace, user, 1024);
        events.publishEvent(request);
        return request.response();
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

    private long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class); }

    private ObjectNode workflow(int attempt, String conclusion) {
        return workflowFor(123, 777, attempt, conclusion);
    }

    private ObjectNode workflowFor(int remoteRepoId, long runId, int attempt, String conclusion) {
        ObjectNode root = json.createObjectNode().put("action", "completed");
        root.putObject("repository").put("id", remoteRepoId);
        root.putObject("workflow_run").put("id", runId).put("run_attempt", attempt).put("head_sha", SHA)
                .put("head_branch", "main").put("status", "completed").put("conclusion", conclusion)
                .put("run_started_at", "2026-11-10T10:00:00Z").put("updated_at", UPDATED)
                .putObject("repository").put("id", remoteRepoId);
        return root;
    }

    private int send(ObjectNode payload, String delivery) throws Exception {
        return sendTo(REPOSITORY, KEY, payload, delivery);
    }

    private int sendTo(UUID repositoryId, String secretKey, ObjectNode payload, String delivery) throws Exception {
        return sendRaw(repositoryId, secretKey, json.writeValueAsBytes(payload), delivery, "workflow_run", "application/json", null)
                .statusCode();
    }

    private HttpResponse<String> sendRaw(UUID repositoryId, String secretKey, byte[] data, String delivery,
                                         String event, String contentType, String contentEncoding) throws Exception {
        Mac hmac = Mac.getInstance("HmacSHA256");
        hmac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/gitci/webhook/github?repositoryId=" + repositoryId))
                .timeout(Duration.ofSeconds(8)).header("Content-Type", contentType)
                .header("X-GitHub-Event", event).header("X-GitHub-Delivery", delivery)
                .header("X-Hub-Signature-256", "sha256=" + HexFormat.of().formatHex(hmac.doFinal(data)))
                .POST(HttpRequest.BodyPublishers.ofByteArray(data));
        if (contentEncoding != null) builder.header("Content-Encoding", contentEncoding);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
