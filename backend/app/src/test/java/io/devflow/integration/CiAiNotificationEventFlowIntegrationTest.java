package io.devflow.integration;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.devflow.common.event.CiFailureDetectedEvent;
import io.devflow.common.event.CiFailureLogReadRequestedEvent;
import io.devflow.common.event.CiFailureLogReadRequestedEvent.Status;
import io.devflow.common.event.CiFailureSummarizedEvent;
import io.devflow.common.event.EventTypes;
import io.devflow.common.event.RiskDeadlineFlaggedEvent;
import io.devflow.gitci.internal.CiMultiModuleTestBase;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * T-041 Suite 4 ({@code CI-01..08}): validates the cross-module CI Failure -> AI Summarizer
 * -> Notification pipeline, synchronous {@code CiFailureLogReadRequestedEvent} access controls,
 * {@code RiskDeadlineFlaggedEvent} delivery, and privacy canary non-leakage on PostgreSQL 17.
 */
@AutoConfigureMockMvc
@RecordApplicationEvents
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "devflow.gitci.outbox.worker-enabled=false",
                "devflow.gitci.ci.fetch-enabled=false",
                "devflow.gitci.ci.publish-enabled=false",
                "devflow.board.git.auto-transition-enabled=true",
                "devflow.ai.chat.model=none"
        })
class CiAiNotificationEventFlowIntegrationTest extends CiMultiModuleTestBase {

    private TenantFixture fx;

    @BeforeEach
    void setupFixture() {
        fx = seedStandardFixture();
        resetGithubActionsStub();
    }

    @Test
    void ci01AndCi03AndCi08_workflowFailureIngestsRedactsSummarizesIdempotentlyAndLeaksNoSecrets(
            ApplicationEvents recorded) throws Exception {
        Logger devflowLogger = (Logger) LoggerFactory.getLogger("io.devflow");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        devflowLogger.addAppender(appender);
        try {
            stubGithubActionsLog("Authorization: Bearer " + GITHUB_PAT_CANARY + "\n"
                    + "Download: " + SIGNED_URL_CANARY + "\n"
                    + "Contact: " + EMAIL_CANARY + "\n"
                    + "Error: AssertionError - expected status 200 but got 500 in OrderControllerTest");

            String sha = hexSha('c', 1001);
            var webhook = sendSignedWebhook(
                    REPO_CI, SECRET_CI_CURRENT, "workflow_run", UUID.randomUUID().toString(),
                    workflowRunPayload(Long.parseLong(REMOTE_REPO_CI), "completed", 8001L, 1,
                            sha, "feature/T-001", "completed", "failure",
                            BASE_TIME, BASE_TIME.plusSeconds(45)));
            assertThat(webhook.statusCode()).isEqualTo(204);

            // Step 1: CiLogFetchWorker downloads & redacts ZIP log and atomically enqueues CI outbox.
            runCiFetchOne();

            UUID internalPipelineId = jdbc.queryForObject(
                    "SELECT id FROM ci_pipelines WHERE repository_id = ? AND pipeline_id = '8001' AND run_attempt = 1",
                    UUID.class, REPO_CI);
            Map<String, Object> failure = jdbc.queryForMap(
                    "SELECT * FROM ci_failures WHERE pipeline_id = ?", internalPipelineId);
            assertThat(failure.get("log_status")).isEqualTo("READY");
            String snippet = (String) failure.get("raw_log_snippet");
            assertThat(snippet).contains("AssertionError - expected status 200");
            assertThat(snippet).doesNotContain(GITHUB_PAT_CANARY, SIGNED_URL_CANARY, EMAIL_CANARY);

            // Step 2: CiFailureEventOutbox publishes CiFailureDetectedEvent -> real AiEventListeners + AiCiExecutor.
            dispatchCiOutbox();

            assertThat(jdbc.queryForObject(
                    "SELECT status FROM ci_failure_event_outbox WHERE pipeline_id = ?",
                    String.class, internalPipelineId)).isEqualTo("PUBLISHED");

            // Wait deterministically for async AiCiExecutor thread to persist ai_ci_summaries.
            awaitCondition(Duration.ofSeconds(10),
                    () -> countRows("ai_ci_summaries") == 1,
                    "Expected real AiEventListeners to persist a CI summary into ai_ci_summaries");

            Map<String, Object> summaryRow = jdbc.queryForMap(
                    "SELECT * FROM ai_ci_summaries WHERE pipeline_id = ?", internalPipelineId.toString());
            String summaryMarkdown = (String) summaryRow.get("summary_markdown");
            assertThat(summaryRow.get("source")).isEqualTo("LLM");
            assertThat(summaryRow.get("commit_sha")).isEqualTo(sha);
            assertThat(summaryMarkdown).isNotBlank();
            assertThat(summaryMarkdown.lines().filter(l -> l.startsWith("- ")).count()).isEqualTo(3);
            assertThat(summaryMarkdown).doesNotContain(GITHUB_PAT_CANARY, SIGNED_URL_CANARY, SECRET_CI_CURRENT);

            // Verify real CiFailureSummarizedEvent was emitted by AiEventListeners (currently relatedTaskId=null per T-031).
            awaitCondition(Duration.ofSeconds(5),
                    () -> recorded.stream(CiFailureSummarizedEvent.class)
                            .anyMatch(e -> internalPipelineId.toString().equals(e.pipelineId())),
                    "Expected CiFailureSummarizedEvent to be published on the in-process bus");

            // CI-03: Replaying the exact same CiFailureDetectedEvent is strictly idempotent in ai_ci_summaries.
            CiFailureDetectedEvent detected = recorded.stream(CiFailureDetectedEvent.class)
                    .filter(e -> internalPipelineId.toString().equals(e.pipelineId()))
                    .findFirst().orElseThrow();
            double aiConsumeBeforeReplay = metrics.counter("devflow.task.events.consume",
                    "eventType", EventTypes.CI_FAILURE_DETECTED, "consumer", "ai", "outcome", "success").count();
            long summarizedCountBeforeReplay = recorded.stream(CiFailureSummarizedEvent.class)
                    .filter(e -> internalPipelineId.toString().equals(e.pipelineId())).count();

            events.publishEvent(detected);
            awaitCondition(Duration.ofSeconds(10),
                    () -> metrics.counter("devflow.task.events.consume",
                            "eventType", EventTypes.CI_FAILURE_DETECTED, "consumer", "ai", "outcome", "success").count()
                            >= aiConsumeBeforeReplay + 1,
                    "Expected replay of CiFailureDetectedEvent to finish on AiCiExecutor");

            assertThat(countRows("ai_ci_summaries")).isEqualTo(1);
            assertThat(recorded.stream(CiFailureSummarizedEvent.class)
                    .filter(e -> internalPipelineId.toString().equals(e.pipelineId())).count())
                    .isEqualTo(summarizedCountBeforeReplay);

            // CI-08: Zero canary leaks across application logs and persisted tables.
            for (ILoggingEvent logEvent : appender.list) {
                String msg = logEvent.getFormattedMessage();
                assertThat(msg).doesNotContain(
                        GITHUB_PAT_CANARY, SIGNED_URL_CANARY, SECRET_CI_CURRENT, EMAIL_CANARY);
            }
        } finally {
            devflowLogger.detachAppender(appender);
            appender.stop();
        }
    }

    @ParameterizedTest
    @CsvSource({
            "completed,completed,success,SUCCESS",
            "completed,completed,cancelled,CANCELLED",
            "completed,completed,skipped,SKIPPED",
            "completed,completed,timed_out,TIMED_OUT",
            "in_progress,in_progress,neutral,IGNORED"
    })
    void ci02_nonFailureAndInProgressWorkflowsNeverTriggerAiOrNotifications(
            String action, String runStatus, String conclusion, String expectedDbStatus) throws Exception {
        long runId = 8100L + Math.abs(conclusion.hashCode() % 1000);
        var response = sendSignedWebhook(
                REPO_CI, SECRET_CI_CURRENT, "workflow_run", UUID.randomUUID().toString(),
                workflowRunPayload(Long.parseLong(REMOTE_REPO_CI), action, runId, 1,
                        hexSha('c', (int) runId), "develop", runStatus, conclusion,
                        BASE_TIME, BASE_TIME.plusSeconds(15)));
        assertThat(response.statusCode()).isEqualTo(204);

        runCiFetchOne();
        dispatchCiOutbox();

        if ("IGNORED".equals(expectedDbStatus)) {
            assertThat(countRows("ci_pipelines")).isZero();
        } else {
            assertThat(jdbc.queryForObject("SELECT status FROM ci_pipelines WHERE pipeline_id = ?",
                    String.class, Long.toString(runId))).isEqualTo(expectedDbStatus);
        }
        assertThat(countRows("ci_failures")).isZero();
        assertThat(countRows("ci_log_fetch_jobs")).isZero();
        assertThat(countRows("ci_failure_event_outbox")).isZero();
        assertThat(countRows("ai_ci_summaries")).isZero();
        assertThat(countRows("notifications")).isZero();
    }

    @Test
    void ci04_synchronousCiFailureLogReadEnforcesProvenanceAuthMembershipAndExpiry(
            ApplicationEvents recorded) throws Exception {
        stubGithubActionsLog("Authorization: Bearer " + GITHUB_PAT_CANARY + "\nError: NullPointerException at Line 42");
        assertThat(sendSignedWebhook(
                REPO_CI, SECRET_CI_CURRENT, "workflow_run", UUID.randomUUID().toString(),
                workflowRunPayload(Long.parseLong(REMOTE_REPO_CI), "completed", 8201L, 1,
                        hexSha('d', 1201), "develop", "completed", "failure",
                        BASE_TIME, BASE_TIME.plusSeconds(20))).statusCode()).isEqualTo(204);
        runCiFetchOne();
        dispatchCiOutbox();

        CiFailureDetectedEvent detected = recorded.stream(CiFailureDetectedEvent.class).findFirst().orElseThrow();
        UUID pipelineUuid = UUID.fromString(detected.pipelineId());
        String dbDigest = jdbc.queryForObject(
                "SELECT log_digest FROM ci_failures WHERE pipeline_id = ?", String.class, pipelineUuid);

        // (a) Valid detection provenance -> READY with redacted excerpt matching digest
        var validProvenance = CiFailureLogReadRequestedEvent.forDetection(UUID.randomUUID(), detected, 16_384);
        events.publishEvent(validProvenance);
        assertThat(validProvenance.response().status()).isEqualTo(Status.READY);
        assertThat(validProvenance.response().excerpt())
                .contains("NullPointerException at Line 42")
                .doesNotContain(GITHUB_PAT_CANARY);
        assertThat(validProvenance.response().digest()).isEqualTo(dbDigest);

        // (b) Forged provenance (wrong eventId & wrong workspaceId) -> DENIED
        var forgedEventId = new CiFailureDetectedEvent(
                detected.pipelineId(), detected.rawLogReference(), detected.repo(), detected.commitSha(),
                detected.occurredAt(), UUID.randomUUID(), detected.repositoryId(), detected.workspaceId(),
                detected.providerRunId(), detected.runAttempt());
        var forgedRead = CiFailureLogReadRequestedEvent.forDetection(UUID.randomUUID(), forgedEventId, 4096);
        events.publishEvent(forgedRead);
        assertThat(forgedRead.response().status()).isEqualTo(Status.DENIED);

        // (c) Authenticated WS-A member via Auth WorkspaceMembershipCheckListener -> READY
        var memberRead = CiFailureLogReadRequestedEvent.forWorkspaceMember(
                UUID.randomUUID(), pipelineUuid, detected.rawLogReference(), fx.wsA(), fx.assigneeA(), 4096);
        events.publishEvent(memberRead);
        assertThat(memberRead.response().status()).isEqualTo(Status.READY);

        // (d) Foreign WS-B outsider via Auth WorkspaceMembershipCheckListener -> DENIED
        var outsiderRead = CiFailureLogReadRequestedEvent.forWorkspaceMember(
                UUID.randomUUID(), pipelineUuid, detected.rawLogReference(), fx.wsA(), fx.assigneeB(), 4096);
        events.publishEvent(outsiderRead);
        assertThat(outsiderRead.response().status()).isEqualTo(Status.DENIED);

        // (e) Expired log -> EXPIRED (keep log_expires_at > log_ready_at to satisfy ck_ci_log_ready)
        jdbc.update("""
                UPDATE ci_failures
                SET log_ready_at = CURRENT_TIMESTAMP - INTERVAL '10 days',
                    log_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 minute'
                WHERE pipeline_id = ?
                """, pipelineUuid);
        var expiredRead = CiFailureLogReadRequestedEvent.forDetection(UUID.randomUUID(), detected, 4096);
        events.publishEvent(expiredRead);
        assertThat(expiredRead.response().status()).isEqualTo(Status.EXPIRED);

        // Drain async AiCiExecutor task triggered by dispatchCiOutbox() so it cannot race with subsequent tests.
        awaitCondition(Duration.ofSeconds(10),
                () -> countRows("ai_ci_summaries") == 1,
                "Expected async AiCiExecutor task from ci04 to finish cleanly before teardown");
    }

    @Test
    void ci05AndCi06_ciFailureSummarizedAndRiskDeadlineFlaggedContractsDeliverDeduplicatedNotifications()
            throws Exception {
        // CI-05a: CiFailureSummarizedEvent with relatedTaskId=null (current T-031 output) skips cleanly
        events.publishEvent(new CiFailureSummarizedEvent("pipe-null-task", "- Summary line", null, BASE_TIME));
        assertThat(countRows("notifications")).isZero();

        // CI-05b: CiFailureSummarizedEvent with resolved Task A1 persists & deduplicates on replay
        CiFailureSummarizedEvent resolved = new CiFailureSummarizedEvent(
                "pipe-task-a1", "- Build failed on compilation", fx.taskA1(), BASE_TIME);
        events.publishEvent(resolved);
        events.publishEvent(resolved);
        assertThat(notificationRows(fx.assigneeA())).hasSize(1);
        assertThat(notificationRows(fx.assigneeA()).get(0))
                .containsEntry("type", EventTypes.CI_FAILURE_SUMMARIZED)
                .containsEntry("workspace_id", fx.wsA())
                .containsEntry("reference_id", fx.taskA1());

        // Unassigned & revoked tasks skipped cleanly
        jdbc.update("DELETE FROM workspace_members WHERE workspace_id = ? AND user_id = ?", fx.wsA(), fx.revokedA());
        events.publishEvent(new CiFailureSummarizedEvent("pipe-unassigned", "- Summary", fx.taskUnassigned(), BASE_TIME));
        events.publishEvent(new CiFailureSummarizedEvent("pipe-revoked", "- Summary", fx.taskRevoked(), BASE_TIME));
        assertThat(notificationRows(fx.revokedA())).isEmpty();
        assertThat(countRows("notifications")).isEqualTo(1);

        // CI-06: RiskDeadlineFlaggedEvent delivers to Assignee A over DB + STOMP, deduplicates replay, skips sprint-only/revoked
        try (var assigneeSocket = connectStomp(fx.assigneeA())) {
            assigneeSocket.subscribeNotifications();

            RiskDeadlineFlaggedEvent taskRisk = new RiskDeadlineFlaggedEvent(
                    fx.taskA1(), null, "Task approaching deadline with open PR",
                    RiskDeadlineFlaggedEvent.Severity.HIGH, BASE_TIME.plusSeconds(5));
            events.publishEvent(taskRisk);
            events.publishEvent(taskRisk);

            // Sprint-only risk (taskId = null) and revoked assignee risk are skipped
            events.publishEvent(new RiskDeadlineFlaggedEvent(
                    null, UUID.randomUUID(), "Sprint velocity behind", RiskDeadlineFlaggedEvent.Severity.MEDIUM,
                    BASE_TIME.plusSeconds(6)));
            events.publishEvent(new RiskDeadlineFlaggedEvent(
                    fx.taskRevoked(), null, "Revoked member task at risk", RiskDeadlineFlaggedEvent.Severity.HIGH,
                    BASE_TIME.plusSeconds(7)));

            Map<?, ?> frame = assigneeSocket.pollNotificationFrame(Duration.ofSeconds(5));
            assertThat(frame).isNotNull();
            assertThat(frame.get("type")).isEqualTo(EventTypes.RISK_DEADLINE_FLAGGED);
            assertThat(frame.get("referenceId")).isEqualTo(fx.taskA1().toString());
            assertThat(assigneeSocket.pollNotificationFrame(Duration.ofMillis(300))).isNull();

            List<Map<String, Object>> notifs = notificationRows(fx.assigneeA());
            assertThat(notifs)
                    .extracting(r -> r.get("type"))
                    .containsExactlyInAnyOrder(EventTypes.CI_FAILURE_SUMMARIZED, EventTypes.RISK_DEADLINE_FLAGGED);
            assertThat(notificationRows(fx.revokedA())).isEmpty();
        }
    }

    @Test
    void ci07_failedRerunAttemptsProduceTwoIndependentPipelinesAndAiSummaries() throws Exception {
        stubGithubActionsLogs("Error: attempt 1 compile error", "Error: attempt 2 test assertion failed");

        assertThat(sendSignedWebhook(
                REPO_CI, SECRET_CI_CURRENT, "workflow_run", UUID.randomUUID().toString(),
                workflowRunPayload(Long.parseLong(REMOTE_REPO_CI), "completed", 8301L, 1,
                        hexSha('e', 1301), "develop", "completed", "failure",
                        BASE_TIME, BASE_TIME.plusSeconds(10))).statusCode()).isEqualTo(204);
        assertThat(sendSignedWebhook(
                REPO_CI, SECRET_CI_CURRENT, "workflow_run", UUID.randomUUID().toString(),
                workflowRunPayload(Long.parseLong(REMOTE_REPO_CI), "completed", 8301L, 2,
                        hexSha('e', 1302), "develop", "completed", "failure",
                        BASE_TIME.plusSeconds(20), BASE_TIME.plusSeconds(35))).statusCode()).isEqualTo(204);

        runCiFetchOne();
        runCiFetchOne();
        dispatchCiOutbox();
        dispatchCiOutbox();

        awaitCondition(Duration.ofSeconds(10),
                () -> countRows("ai_ci_summaries") == 2,
                "Expected two independent AI summaries for attempt 1 and attempt 2");

        assertThat(countRows("ci_pipelines")).isEqualTo(2);
        assertThat(countRows("ci_failures")).isEqualTo(2);
        assertThat(countRows("ci_failure_event_outbox")).isEqualTo(2);
        List<String> summaryPipelineIds = jdbc.queryForList("SELECT pipeline_id FROM ai_ci_summaries", String.class);
        List<String> attemptUuids = jdbc.queryForList("SELECT id::text FROM ci_pipelines", String.class);
        assertThat(summaryPipelineIds).containsExactlyInAnyOrderElementsOf(attemptUuids);
    }
}
