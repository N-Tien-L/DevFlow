package io.devflow.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import io.devflow.common.event.DevFlowEvent;
import io.devflow.common.event.EventTypes;
import io.devflow.common.event.GitCommitLinkedEvent;
import io.devflow.common.event.GitPrOpenedEvent;
import io.devflow.common.event.TaskStatusChangedEvent;
import io.devflow.common.security.AuthenticatedActor;
import io.micrometer.core.instrument.Meter;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MvcResult;

/**
 * T-041 core multi-module integration test suite validating the in-process Spring Event Bus
 * across {@code gitci}, {@code board}, {@code auth}, {@code notification}, and {@code ai}
 * on real PostgreSQL 17 + Flyway + real HTTP/HMAC + real dual-channel STOMP WebSockets.
 *
 * <p>Covers test suites 1 ({@code E2E-01..06, E2E-08..09, E2E-11..12}), 2 ({@code NEG-01..12}),
 * 3 ({@code IDEM-01..10}), and operational checks ({@code ARCH-05, ARCH-06}).
 */
@AutoConfigureMockMvc
@AutoConfigureObservability(tracing = false)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "devflow.gitci.outbox.worker-enabled=false",
                "devflow.gitci.ci.fetch-enabled=false",
                "devflow.gitci.ci.publish-enabled=false",
                "devflow.board.git.auto-transition-enabled=true",
                "devflow.board.tasks.max-per-column=5",
                "management.endpoints.web.exposure.include=health,info,prometheus",
                "management.endpoint.health.show-details=always"
        })
class GitBoardNotificationFlowIntegrationTest extends MultiModulePostgresSupport {

    private TenantFixture fx;

    @BeforeEach
    void setupFixture() {
        fx = seedStandardFixture();
    }

    // -------------------------------------------------------------------------
    // Suite 1: Core Push & PR -> Event Bus -> Board -> Notification + STOMP
    // -------------------------------------------------------------------------

    @Test
    void e2e01AndE2e11_signedPushWebhookTransitionsTaskPersistsNotificationFansOutToAiAndServesRest()
            throws Exception {
        double dispatchBefore = metrics.counter("devflow.task.events.dispatch",
                "eventType", EventTypes.TASK_STATUS_CHANGED, "outcome", "success").count();
        double notifConsumeBefore = metrics.counter("devflow.task.events.consume",
                "eventType", EventTypes.TASK_STATUS_CHANGED, "consumer", "notification", "outcome", "success").count();
        double aiConsumeBefore = metrics.counter("devflow.task.events.consume",
                "eventType", EventTypes.TASK_STATUS_CHANGED, "consumer", "ai", "outcome", "success").count();

        String sha = hexSha('a', 101);
        var webhookResponse = sendSignedWebhook(
                REPO_WS_A, SECRET_A_CURRENT, "push", UUID.randomUUID().toString(),
                pushPayload(Long.parseLong(REMOTE_REPO_A), "feature/T-001-core", sha,
                        "feat: implement T-001 core flow", BASE_TIME, "dev-xuan"));
        assertThat(webhookResponse.statusCode()).isEqualTo(204);

        // Before outbox dispatch: GitCI persisted receipt, commit, link and PENDING outbox; Board/Notification untouched.
        assertThat(jdbc.queryForObject("SELECT status FROM github_webhook_receipts", String.class))
                .isEqualTo("COMPLETED");
        assertThat(countRows("git_commits")).isEqualTo(1);
        assertThat(countRows("git_commit_task_links")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM git_domain_event_outbox", String.class))
                .isEqualTo("PENDING");
        assertThat(taskRow(fx.taskA1()).get("column_id")).isEqualTo(fx.colsA().get("TODO"));
        assertThat(countRows("notifications")).isZero();

        // Dispatch on clean thread (mirrors @Scheduled outbox worker).
        SecurityContextHolder.clearContext();
        gitOutbox.dispatch();

        // Oracle 2 (PostgreSQL 17): Outbox PUBLISHED, Board moved + audited + receipted, Notification persisted.
        Map<String, Object> outboxRow = jdbc.queryForMap("SELECT * FROM git_domain_event_outbox");
        assertThat(outboxRow.get("status")).isEqualTo("PUBLISHED");
        assertThat(outboxRow.get("attempts")).isEqualTo(1);
        UUID outboxEventId = (UUID) outboxRow.get("id");

        Map<String, Object> taskAfter = taskRow(fx.taskA1());
        assertThat(taskAfter.get("column_id")).isEqualTo(fx.colsA().get("IN_PROGRESS"));
        assertThat(taskAfter.get("version")).isEqualTo(1L);

        Map<String, Object> receipt = jdbc.queryForMap("SELECT * FROM board_git_event_receipts");
        assertThat(receipt.get("event_id")).isEqualTo(outboxEventId);
        assertThat(receipt.get("outcome")).isEqualTo("APPLIED");

        Map<String, Object> audit = jdbc.queryForMap("SELECT * FROM board_audit_events WHERE resource_id = ?", fx.taskA1());
        assertThat(audit.get("cause")).isEqualTo("GIT");
        assertThat(audit.get("actor_id")).isNull();
        assertThat(audit.get("source_event_id")).isEqualTo(outboxEventId);
        assertThat(audit.get("destination_column_id")).isEqualTo(fx.colsA().get("IN_PROGRESS"));

        List<Map<String, Object>> assigneeNotifs = notificationRows(fx.assigneeA());
        assertThat(assigneeNotifs).hasSize(1);
        assertThat(assigneeNotifs.get(0))
                .containsEntry("type", EventTypes.TASK_STATUS_CHANGED)
                .containsEntry("workspace_id", fx.wsA())
                .containsEntry("reference_id", fx.taskA1())
                .containsEntry("read_at", null);
        assertThat(notificationRows(fx.collabA())).isEmpty();
        assertThat(notificationRows(fx.assigneeB())).isEmpty();

        // Oracle 1 (REST API): Board task endpoint & Notification REST endpoints reflect committed DB state.
        mvc.perform(get("/api/v1/tasks/" + fx.taskA1()).header("Authorization", bearer(fx.assigneeA())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columnId").value(fx.colsA().get("IN_PROGRESS").toString()))
                .andExpect(jsonPath("$.version").value(1));

        mvc.perform(get("/api/v1/notifications/unread-count").header("Authorization", bearer(fx.assigneeA())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));

        mvc.perform(get("/api/v1/notifications").header("Authorization", bearer(fx.assigneeA())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].type").value(EventTypes.TASK_STATUS_CHANGED))
                .andExpect(jsonPath("$.content[0].referenceId").value(fx.taskA1().toString()))
                .andExpect(jsonPath("$.content[0].workspaceId").value(fx.wsA().toString()))
                .andExpect(jsonPath("$.content[0].isRead").value(false));

        // Oracle 4 (Metrics — E2E-11): TaskStatusChangedEvent fanned out to BOTH Notification and AI modules.
        assertThat(metrics.counter("devflow.task.events.dispatch",
                "eventType", EventTypes.TASK_STATUS_CHANGED, "outcome", "success").count())
                .isEqualTo(dispatchBefore + 1);
        assertThat(metrics.counter("devflow.task.events.consume",
                "eventType", EventTypes.TASK_STATUS_CHANGED, "consumer", "notification", "outcome", "success").count())
                .isEqualTo(notifConsumeBefore + 1);
        assertThat(metrics.counter("devflow.task.events.consume",
                "eventType", EventTypes.TASK_STATUS_CHANGED, "consumer", "ai", "outcome", "success").count())
                .isEqualTo(aiConsumeBefore + 1);
    }

    @Test
    void e2e02AndE2e03AndE2e04AndE2e06_fullGitLifecyclePushPrOpenPrMergeAndNotificationReadFlow()
            throws Exception {
        // Step 1: Push -> IN_PROGRESS (1 notification: task.status_changed)
        assertThat(sendSignedWebhook(REPO_WS_A, SECRET_A_CURRENT, "push", UUID.randomUUID().toString(),
                pushPayload(Long.parseLong(REMOTE_REPO_A), "feature/T-001", hexSha('b', 201),
                        "feat(board): T-001 step 1", BASE_TIME, "dev-xuan")).statusCode()).isEqualTo(204);
        gitOutbox.dispatch();
        assertThat(taskRow(fx.taskA1()).get("column_id")).isEqualTo(fx.colsA().get("IN_PROGRESS"));
        assertThat(notificationRows(fx.assigneeA()))
                .extracting(r -> r.get("type"))
                .containsExactly(EventTypes.TASK_STATUS_CHANGED);

        // Step 2: PR opened -> IN_REVIEW (+2 notifications: task.status_changed & git.pr_opened)
        assertThat(sendSignedWebhook(REPO_WS_A, SECRET_A_CURRENT, "pull_request", UUID.randomUUID().toString(),
                prPayload(Long.parseLong(REMOTE_REPO_A), "opened", 42, "T-001: Open PR for review",
                        "feature/T-001", "develop", "open", false, BASE_TIME.plusSeconds(10))).statusCode())
                .isEqualTo(204);
        gitOutbox.dispatch();
        assertThat(taskRow(fx.taskA1()).get("column_id")).isEqualTo(fx.colsA().get("IN_REVIEW"));
        assertThat(jdbc.queryForObject("SELECT status FROM pull_requests WHERE pr_number = 42", String.class))
                .isEqualTo("OPEN");
        assertThat(notificationRows(fx.assigneeA())).hasSize(3);
        assertThat(notificationRows(fx.assigneeA()))
                .extracting(r -> r.get("type"))
                .containsExactlyInAnyOrder(
                        EventTypes.TASK_STATUS_CHANGED,
                        EventTypes.TASK_STATUS_CHANGED,
                        EventTypes.GIT_PR_OPENED);

        // Step 3: PR merged -> DONE (+2 notifications: task.status_changed & git.pr_merged)
        assertThat(sendSignedWebhook(REPO_WS_A, SECRET_A_CURRENT, "pull_request", UUID.randomUUID().toString(),
                prPayload(Long.parseLong(REMOTE_REPO_A), "closed", 42, "T-001: Open PR for review",
                        "feature/T-001", "develop", "closed", true, BASE_TIME.plusSeconds(20))).statusCode())
                .isEqualTo(204);
        gitOutbox.dispatch();

        assertThat(taskRow(fx.taskA1()).get("column_id")).isEqualTo(fx.colsA().get("DONE"));
        assertThat(taskRow(fx.taskA1()).get("version")).isEqualTo(3L);
        assertThat(jdbc.queryForObject("SELECT status FROM pull_requests WHERE pr_number = 42", String.class))
                .isEqualTo("MERGED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM git_domain_event_outbox WHERE status = 'PUBLISHED'", Long.class))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM board_git_event_receipts WHERE outcome = 'APPLIED'", Long.class))
                .isEqualTo(3);
        assertThat(countRows("board_audit_events")).isEqualTo(3);

        List<Map<String, Object>> allNotifs = notificationRows(fx.assigneeA());
        assertThat(allNotifs).hasSize(5);
        assertThat(allNotifs)
                .extracting(r -> r.get("type"))
                .containsExactlyInAnyOrder(
                        EventTypes.TASK_STATUS_CHANGED,
                        EventTypes.TASK_STATUS_CHANGED,
                        EventTypes.TASK_STATUS_CHANGED,
                        EventTypes.GIT_PR_OPENED,
                        EventTypes.GIT_PR_MERGED);
        assertThat(allNotifs).allSatisfy(row -> {
            assertThat(row.get("workspace_id")).isEqualTo(fx.wsA());
            assertThat(row.get("reference_id")).isEqualTo(fx.taskA1());
        });
        assertThat(notificationRows(fx.collabA())).isEmpty();

        // E2E-06: REST list, unread-count, mark single read, and mark-all-read
        MvcResult listResult = mvc.perform(get("/api/v1/notifications?page=0&size=20")
                        .header("Authorization", bearer(fx.assigneeA())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(5))
                .andReturn();
        JsonNode listJson = json.readTree(listResult.getResponse().getContentAsString());
        String firstNotificationId = listJson.path("content").get(0).path("id").asText();

        mvc.perform(get("/api/v1/notifications/unread-count").header("Authorization", bearer(fx.assigneeA())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(5));

        mvc.perform(patch("/api/v1/notifications/" + firstNotificationId + "/read")
                        .header("Authorization", bearer(fx.assigneeA())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(firstNotificationId))
                .andExpect(jsonPath("$.isRead").value(true))
                .andExpect(jsonPath("$.readAt").isNotEmpty());

        mvc.perform(get("/api/v1/notifications/unread-count").header("Authorization", bearer(fx.assigneeA())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(4));

        mvc.perform(post("/api/v1/notifications/read-all").header("Authorization", bearer(fx.assigneeA())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(4));

        mvc.perform(get("/api/v1/notifications/unread-count").header("Authorization", bearer(fx.assigneeA())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE recipient_id = ? AND read_at IS NULL",
                Long.class, fx.assigneeA())).isZero();
    }

    @Test
    void e2e05_singleCommitReferencingTwoTasksRoutesNotificationsOnlyToEachTasksAssignee() throws Exception {
        assertThat(sendSignedWebhook(REPO_WS_A_ALT, SECRET_A_ALT_CURRENT, "push", UUID.randomUUID().toString(),
                pushPayload(Long.parseLong(REMOTE_REPO_A_ALT), "feature/multi", hexSha('c', 301),
                        "fix: coordinate T-001 and T-002 together", BASE_TIME, "dev-xuan")).statusCode())
                .isEqualTo(204);
        gitOutbox.dispatch();

        assertThat(countRows("git_commits")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT task_id FROM git_commits", UUID.class)).isNull();
        assertThat(countRows("git_commit_task_links")).isEqualTo(2);
        assertThat(taskRow(fx.taskA1()).get("column_id")).isEqualTo(fx.colsA().get("IN_PROGRESS"));
        assertThat(taskRow(fx.taskA2()).get("column_id")).isEqualTo(fx.colsA().get("IN_PROGRESS"));

        List<Map<String, Object>> notifA1 = notificationRows(fx.assigneeA());
        List<Map<String, Object>> notifA2 = notificationRows(fx.assigneeA2());
        assertThat(notifA1).hasSize(1);
        assertThat(notifA1.get(0).get("reference_id")).isEqualTo(fx.taskA1());
        assertThat(notifA2).hasSize(1);
        assertThat(notifA2.get(0).get("reference_id")).isEqualTo(fx.taskA2());
        assertThat(notificationRows(fx.collabA())).isEmpty();
    }

    @Test
    void e2e08AndE2e09_secondPushNoopAndNonTransitionPrActionsDoNotSpamNotifications() throws Exception {
        // Push #1 moves T-001 -> IN_PROGRESS (1 notification)
        assertThat(sendSignedWebhook(REPO_WS_A, SECRET_A_CURRENT, "push", UUID.randomUUID().toString(),
                pushPayload(Long.parseLong(REMOTE_REPO_A), "feature/T-001", hexSha('d', 401),
                        "feat: T-001 first commit", BASE_TIME, "dev-xuan")).statusCode()).isEqualTo(204);
        gitOutbox.dispatch();
        assertThat(notificationRows(fx.assigneeA())).hasSize(1);

        // E2E-08: Push #2 while already IN_PROGRESS -> NOOP, 0 additional notifications
        assertThat(sendSignedWebhook(REPO_WS_A, SECRET_A_CURRENT, "push", UUID.randomUUID().toString(),
                pushPayload(Long.parseLong(REMOTE_REPO_A), "feature/T-001", hexSha('d', 402),
                        "feat: T-001 second commit", BASE_TIME.plusSeconds(5), "dev-xuan")).statusCode()).isEqualTo(204);
        gitOutbox.dispatch();
        assertThat(countRows("git_commits")).isEqualTo(2);
        assertThat(taskRow(fx.taskA1()).get("column_id")).isEqualTo(fx.colsA().get("IN_PROGRESS"));
        assertThat(taskRow(fx.taskA1()).get("version")).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM board_git_event_receipts WHERE outcome = 'NOOP'", Long.class)).isEqualTo(1);
        assertThat(notificationRows(fx.assigneeA())).hasSize(1);

        // E2E-09: PR opened -> edited -> closed (not merged) -> reopened
        assertThat(sendSignedWebhook(REPO_WS_A, SECRET_A_CURRENT, "pull_request", UUID.randomUUID().toString(),
                prPayload(Long.parseLong(REMOTE_REPO_A), "opened", 50, "T-001: Initial PR",
                        "feature/T-001", "develop", "open", false, BASE_TIME.plusSeconds(10))).statusCode())
                .isEqualTo(204);
        gitOutbox.dispatch();
        long outboxAfterOpen = countRows("git_domain_event_outbox");
        int notifsAfterOpen = notificationRows(fx.assigneeA()).size();
        assertThat(notifsAfterOpen).isEqualTo(3);

        assertThat(sendSignedWebhook(REPO_WS_A, SECRET_A_CURRENT, "pull_request", UUID.randomUUID().toString(),
                prPayload(Long.parseLong(REMOTE_REPO_A), "edited", 50, "T-001: Refined PR title",
                        "feature/T-001", "develop", "open", false, BASE_TIME.plusSeconds(20))).statusCode())
                .isEqualTo(204);
        assertThat(sendSignedWebhook(REPO_WS_A, SECRET_A_CURRENT, "pull_request", UUID.randomUUID().toString(),
                prPayload(Long.parseLong(REMOTE_REPO_A), "closed", 50, "T-001: Refined PR title",
                        "feature/T-001", "develop", "closed", false, BASE_TIME.plusSeconds(30))).statusCode())
                .isEqualTo(204);
        assertThat(sendSignedWebhook(REPO_WS_A, SECRET_A_CURRENT, "pull_request", UUID.randomUUID().toString(),
                prPayload(Long.parseLong(REMOTE_REPO_A), "reopened", 50, "T-001: Refined PR title",
                        "feature/T-001", "develop", "open", false, BASE_TIME.plusSeconds(40))).statusCode())
                .isEqualTo(204);
        gitOutbox.dispatch();

        assertThat(jdbc.queryForObject("SELECT status FROM pull_requests WHERE pr_number = 50", String.class))
                .isEqualTo("OPEN");
        assertThat(countRows("git_domain_event_outbox")).isEqualTo(outboxAfterOpen);
        assertThat(taskRow(fx.taskA1()).get("column_id")).isEqualTo(fx.colsA().get("IN_REVIEW"));
        assertThat(notificationRows(fx.assigneeA())).hasSize(notifsAfterOpen);
    }

    @Test
    void e2e12_dualChannelStompDeliversBoardCardMovedToAllMembersAndNotificationOnlyToAssignee() throws Exception {
        try (var assigneeSocket = connectStomp(fx.assigneeA());
             var collabSocket = connectStomp(fx.collabA());
             var outsiderSocket = connectStomp(fx.assigneeB())) {
            assigneeSocket.subscribeBoard(fx.boardA());
            assigneeSocket.subscribeNotifications();
            collabSocket.subscribeBoard(fx.boardA());
            collabSocket.subscribeNotifications();
            outsiderSocket.subscribeNotifications();

            assertThat(sendSignedWebhook(REPO_WS_A_ALT, SECRET_A_ALT_CURRENT, "push", UUID.randomUUID().toString(),
                    pushPayload(Long.parseLong(REMOTE_REPO_A_ALT), "feature/T-001", hexSha('e', 501),
                            "feat: T-001 realtime verification", BASE_TIME, "dev-xuan")).statusCode())
                    .isEqualTo(204);
            gitOutbox.dispatch();

            // Both workspace members watching Board A see CARD_MOVED.
            for (var socket : List.of(assigneeSocket, collabSocket)) {
                Map<?, ?> boardFrame = socket.pollBoardFrame(Duration.ofSeconds(5));
                assertThat(boardFrame).isNotNull();
                assertThat(boardFrame.get("type")).isEqualTo("CARD_MOVED");
                assertThat(boardFrame.get("actorId")).isNull();
                Map<?, ?> data = (Map<?, ?>) boardFrame.get("data");
                assertThat(data.get("taskId")).isEqualTo(fx.taskA1().toString());
                assertThat(data.get("destinationColumnId")).isEqualTo(fx.colsA().get("IN_PROGRESS").toString());
            }

            // Only Assignee A receives the personal STOMP notification on /user/queue/notifications.
            Map<?, ?> notifFrame = assigneeSocket.pollNotificationFrame(Duration.ofSeconds(5));
            assertThat(notifFrame).isNotNull();
            assertThat(notifFrame.get("type")).isEqualTo(EventTypes.TASK_STATUS_CHANGED);
            assertThat(notifFrame.get("workspaceId")).isEqualTo(fx.wsA().toString());
            assertThat(notifFrame.get("referenceId")).isEqualTo(fx.taskA1().toString());
            assertThat(notifFrame.get("isRead")).isEqualTo(false);

            assertThat(collabSocket.pollNotificationFrame(Duration.ofMillis(400))).isNull();
            assertThat(outsiderSocket.pollNotificationFrame(Duration.ofMillis(400))).isNull();
        }
    }

    // -------------------------------------------------------------------------
    // Suite 2: Rejected Webhooks, Security, Anti-Spoofing & Tenant Isolation
    // -------------------------------------------------------------------------

    @Test
    void neg01AndNeg03AndNeg04_invalidSignaturesMismatchedRepoAndMalformedPayloadsCauseZeroSideEffects()
            throws Exception {
        Map<String, Object> validPush = pushPayload(Long.parseLong(REMOTE_REPO_A), "feature/T-001",
                hexSha('f', 601), "feat: T-001 rejected", BASE_TIME, "dev-xuan");
        byte[] validBytes = json.writeValueAsBytes(validPush);
        String validSig = signHmac(validBytes, SECRET_A_CURRENT);

        // NEG-01a: missing signature
        var rMissingSig = sendRawWebhook(REPO_WS_A, null, "push", UUID.randomUUID().toString(),
                "application/json", validBytes);
        assertProblemResponse(rMissingSig, 401, "WEBHOOK_UNAUTHORIZED");

        // NEG-01b: wrong signature
        var rWrongSig = sendRawWebhook(REPO_WS_A, "sha256=" + "0".repeat(64), "push", UUID.randomUUID().toString(),
                "application/json", validBytes);
        assertProblemResponse(rWrongSig, 401, "WEBHOOK_UNAUTHORIZED");

        // NEG-01c: body tampered after signing
        byte[] tamperedBytes = (new String(validBytes, StandardCharsets.UTF_8) + " ").getBytes(StandardCharsets.UTF_8);
        var rTampered = sendRawWebhook(REPO_WS_A, validSig, "push", UUID.randomUUID().toString(),
                "application/json", tamperedBytes);
        assertProblemResponse(rTampered, 401, "WEBHOOK_UNAUTHORIZED");

        // NEG-01d: unknown repositoryId binding
        var rUnknownRepo = sendRawWebhook(UUID.randomUUID(), validSig, "push", UUID.randomUUID().toString(),
                "application/json", validBytes);
        assertProblemResponse(rUnknownRepo, 401, "WEBHOOK_UNAUTHORIZED");

        // NEG-03: valid HMAC for Repo A, but JSON repository.id belongs to Repo B
        var rRepoMismatch = sendSignedWebhook(REPO_WS_A, SECRET_A_CURRENT, "push", UUID.randomUUID().toString(),
                pushPayload(Long.parseLong(REMOTE_REPO_B), "feature/T-001", hexSha('f', 602),
                        "feat: T-001 spoofed repo", BASE_TIME, "dev-xuan"));
        assertProblemResponse(rRepoMismatch, 401, "WEBHOOK_UNAUTHORIZED");

        // NEG-04a: malformed JSON with trailing token
        byte[] badJson = "{\"repository\":{\"id\":41001}} trailing".getBytes(StandardCharsets.UTF_8);
        assertProblemResponse(sendRawWebhook(REPO_WS_A, signHmac(badJson, SECRET_A_CURRENT), "push",
                UUID.randomUUID().toString(), "application/json", badJson), 400, "WEBHOOK_INVALID_PAYLOAD");

        // NEG-04b: all-zero commit SHA
        assertProblemResponse(sendSignedWebhook(REPO_WS_A, SECRET_A_CURRENT, "push", UUID.randomUUID().toString(),
                pushPayload(Long.parseLong(REMOTE_REPO_A), "feature/T-001", "0".repeat(40),
                        "feat: T-001 zero sha", BASE_TIME, "dev-xuan")), 400, "GIT_INVALID_PAYLOAD");

        // NEG-04c: missing X-GitHub-Delivery header
        assertProblemResponse(sendRawWebhook(REPO_WS_A, validSig, "push", null, "application/json", validBytes),
                400, "WEBHOOK_INVALID_HEADER");

        // NEG-04d: unsupported media type
        assertProblemResponse(sendRawWebhook(REPO_WS_A, validSig, "push", UUID.randomUUID().toString(),
                "text/plain", validBytes), 415, "WEBHOOK_UNSUPPORTED_MEDIA");

        // NEG-04e: oversized body (> 65536 bytes configured in support)
        byte[] oversized = new byte[70_000];
        assertProblemResponse(sendRawWebhook(REPO_WS_A, signHmac(oversized, SECRET_A_CURRENT), "push",
                UUID.randomUUID().toString(), "application/json", oversized), 413, "WEBHOOK_TOO_LARGE");

        // NEG-04f: declared size > commits.size() (incomplete push)
        Map<String, Object> incompletePush = Map.of(
                "repository", Map.of("id", Long.parseLong(REMOTE_REPO_A)),
                "ref", "refs/heads/main",
                "after", hexSha('f', 603),
                "deleted", false,
                "size", 5,
                "commits", List.of(Map.of("id", hexSha('f', 603), "message", "T-001", "timestamp", BASE_TIME.toString())));
        assertProblemResponse(sendSignedWebhook(REPO_WS_A, SECRET_A_CURRENT, "push", UUID.randomUUID().toString(),
                incompletePush), 422, "GIT_INCOMPLETE_PUSH");

        gitOutbox.dispatch();

        // Full database zero-side-effect verification
        assertThat(countRows("github_webhook_receipts")).isZero();
        assertThat(countRows("git_commits")).isZero();
        assertThat(countRows("git_commit_task_links")).isZero();
        assertThat(countRows("git_domain_event_outbox")).isZero();
        assertThat(countRows("board_git_event_receipts")).isZero();
        assertThat(countRows("board_audit_events")).isZero();
        assertThat(countRows("notifications")).isZero();
        assertThat(taskRow(fx.taskA1()).get("column_id")).isEqualTo(fx.colsA().get("TODO"));
        assertThat(taskRow(fx.taskA1()).get("version")).isEqualTo(0L);
    }

    @Test
    void neg02_keyRotationAcceptsCurrentAndValidPreviousButRejectsExpiredPrevious() throws Exception {
        // Valid previous key on Repo A moves T-001 -> IN_PROGRESS and notifies Assignee A
        assertThat(sendSignedWebhook(REPO_WS_A, SECRET_A_PREVIOUS_VALID, "push", UUID.randomUUID().toString(),
                pushPayload(Long.parseLong(REMOTE_REPO_A), "feature/T-001", hexSha('1', 11),
                        "feat: T-001 signed with valid previous key", BASE_TIME, "dev-xuan")).statusCode())
                .isEqualTo(204);
        gitOutbox.dispatch();
        assertThat(taskRow(fx.taskA1()).get("column_id")).isEqualTo(fx.colsA().get("IN_PROGRESS"));
        assertThat(notificationRows(fx.assigneeA())).hasSize(1);

        // Expired previous key on Repo A Alt is rejected (401) and leaves T-002 untouched
        var expired = sendSignedWebhook(REPO_WS_A_ALT, SECRET_A_ALT_PREVIOUS_EXPIRED, "push", UUID.randomUUID().toString(),
                pushPayload(Long.parseLong(REMOTE_REPO_A_ALT), "feature/T-002", hexSha('1', 12),
                        "feat: T-002 signed with expired key", BASE_TIME, "dev-xuan"));
        assertProblemResponse(expired, 401, "WEBHOOK_UNAUTHORIZED");
        gitOutbox.dispatch();
        assertThat(taskRow(fx.taskA2()).get("column_id")).isEqualTo(fx.colsA().get("TODO"));
        assertThat(notificationRows(fx.assigneeA2())).isEmpty();

        // Current key on Repo A Alt succeeds for T-002
        assertThat(sendSignedWebhook(REPO_WS_A_ALT, SECRET_A_ALT_CURRENT, "push", UUID.randomUUID().toString(),
                pushPayload(Long.parseLong(REMOTE_REPO_A_ALT), "feature/T-002", hexSha('1', 13),
                        "feat: T-002 signed with current key", BASE_TIME, "dev-xuan")).statusCode())
                .isEqualTo(204);
        gitOutbox.dispatch();
        assertThat(taskRow(fx.taskA2()).get("column_id")).isEqualTo(fx.colsA().get("IN_PROGRESS"));
        assertThat(notificationRows(fx.assigneeA2())).hasSize(1);
    }

    @Test
    void neg05AndNeg06AndNeg07_missingTaskCrossTenantKeyAndArchivedBoardNeverCauseWrongTransitionsOrNotifications()
            throws Exception {
        // Insert extra tasks in WS-B so WS-B has T-002..T-006 while WS-A only has T-001..T-005.
        UUID taskB6 = null;
        for (int i = 1; i <= 5; i++) {
            taskB6 = insertTask(fx.colsB().get("TODO"), "Foreign Task B" + (i + 1), i, fx.assigneeB());
        }
        assertThat(jdbc.queryForObject("SELECT task_number FROM tasks WHERE id = ?", Integer.class, taskB6))
                .isEqualTo(6);

        // NEG-05: Unknown key T-999 on Repo A
        assertThat(sendSignedWebhook(REPO_WS_A, SECRET_A_CURRENT, "push", UUID.randomUUID().toString(),
                pushPayload(Long.parseLong(REMOTE_REPO_A), "feature/unknown", hexSha('2', 21),
                        "chore: T-999 nonexistent task", BASE_TIME, "dev-xuan")).statusCode()).isEqualTo(204);

        // NEG-07: Archived task T-005 on Repo A
        assertThat(sendSignedWebhook(REPO_WS_A, SECRET_A_CURRENT, "push", UUID.randomUUID().toString(),
                pushPayload(Long.parseLong(REMOTE_REPO_A), "feature/archived", hexSha('2', 22),
                        "chore: T-005 archived board task", BASE_TIME.plusSeconds(1), "dev-xuan")).statusCode())
                .isEqualTo(204);

        // NEG-06: Repo A mentions T-001 (exists in BOTH WS-A and WS-B) AND T-006 (exists ONLY in WS-B)
        assertThat(sendSignedWebhook(REPO_WS_A, SECRET_A_CURRENT, "push", UUID.randomUUID().toString(),
                pushPayload(Long.parseLong(REMOTE_REPO_A), "feature/tenant", hexSha('2', 23),
                        "feat: T-001 and T-006 cross-tenant test", BASE_TIME.plusSeconds(2), "dev-xuan")).statusCode())
                .isEqualTo(204);
        gitOutbox.dispatch();

        // Only WS-A's T-001 transitioned and notified Assignee A
        assertThat(taskRow(fx.taskA1()).get("column_id")).isEqualTo(fx.colsA().get("IN_PROGRESS"));
        assertThat(notificationRows(fx.assigneeA())).hasSize(1);
        assertThat(notificationRows(fx.assigneeA()).get(0).get("reference_id")).isEqualTo(fx.taskA1());

        // Archived T-005 and WS-B's T-001 / T-006 completely untouched; Assignee B gets 0 notifications
        assertThat(taskRow(fx.taskArchived()).get("column_id")).isEqualTo(fx.colsArchived().get("TODO"));
        assertThat(taskRow(fx.taskB1()).get("column_id")).isEqualTo(fx.colsB().get("TODO"));
        assertThat(taskRow(taskB6).get("column_id")).isEqualTo(fx.colsB().get("TODO"));
        assertThat(notificationRows(fx.assigneeB())).isEmpty();
        assertThat(countRows("git_commit_task_links")).isEqualTo(1);
    }

    @Test
    void neg08AndNeg09_unassignedTaskAndRevokedMemberTransitionBoardWithoutCreatingNotifications()
            throws Exception {
        // NEG-08: Unassigned task T-003 transitions cleanly with 0 notifications
        assertThat(sendSignedWebhook(REPO_WS_A_ALT, SECRET_A_ALT_CURRENT, "push", UUID.randomUUID().toString(),
                pushPayload(Long.parseLong(REMOTE_REPO_A_ALT), "feature/T-003", hexSha('3', 31),
                        "feat: T-003 unassigned", BASE_TIME, "dev-xuan")).statusCode()).isEqualTo(204);
        gitOutbox.dispatch();
        assertThat(taskRow(fx.taskUnassigned()).get("column_id")).isEqualTo(fx.colsA().get("IN_PROGRESS"));
        assertThat(countRows("notifications")).isZero();

        // NEG-09: Connect revokedA while still a member, then revoke membership before webhook fires
        try (var revokedSocket = connectStomp(fx.revokedA())) {
            revokedSocket.subscribeBoard(fx.boardA());
            revokedSocket.subscribeNotifications();

            double deniedBefore = metrics.counter("devflow.board.realtime.deliveries", "outcome", "denied").count();
            jdbc.update("DELETE FROM workspace_members WHERE workspace_id = ? AND user_id = ?",
                    fx.wsA(), fx.revokedA());

            assertThat(sendSignedWebhook(REPO_WS_A_ALT, SECRET_A_ALT_CURRENT, "push", UUID.randomUUID().toString(),
                    pushPayload(Long.parseLong(REMOTE_REPO_A_ALT), "feature/T-004", hexSha('3', 32),
                            "feat: T-004 revoked assignee", BASE_TIME.plusSeconds(1), "dev-xuan")).statusCode())
                    .isEqualTo(204);
            assertThat(sendSignedWebhook(REPO_WS_A_ALT, SECRET_A_ALT_CURRENT, "pull_request", UUID.randomUUID().toString(),
                    prPayload(Long.parseLong(REMOTE_REPO_A_ALT), "opened", 44, "T-004 revoked PR",
                            "feature/T-004", "develop", "open", false, BASE_TIME.plusSeconds(2))).statusCode())
                    .isEqualTo(204);
            gitOutbox.dispatch();

            assertThat(taskRow(fx.taskRevoked()).get("column_id")).isEqualTo(fx.colsA().get("IN_REVIEW"));
            assertThat(notificationRows(fx.revokedA())).isEmpty();

            awaitCondition(Duration.ofSeconds(5),
                    () -> metrics.counter("devflow.board.realtime.deliveries", "outcome", "denied").count() > deniedBefore,
                    "Expected Board outbound interceptor to deny delivery to revoked member");
            assertThat(revokedSocket.pollBoardFrame(Duration.ofMillis(300))).isNull();
            assertThat(revokedSocket.pollNotificationFrame(Duration.ofMillis(300))).isNull();

            mvc.perform(get("/api/v1/tasks/" + fx.taskRevoked()).header("Authorization", bearer(fx.revokedA())))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    void neg10_gitPrEventIgnoresAmbientSecurityContextAndCleanWorkerDeliversGitStatusNotification() {
        // Put Assignee A into SecurityContextHolder to prove GitPrOpenedEvent never misattributes actor.
        AuthenticatedActor actor = fx::assigneeA;
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(actor, null, List.of()));
        try {
            events.publishEvent(new GitPrOpenedEvent(
                    fx.taskA1(), "https://github.com/devflow-test/app-a/pull/77", "devflow-test/app-a",
                    null, BASE_TIME, UUID.randomUUID(), REPO_WS_A, fx.wsA()));
            // NotificationEventListeners treats GitPrOpenedEvent as gitEvent -> still delivers git.pr_opened.
            assertThat(notificationRows(fx.assigneeA()))
                    .extracting(r -> r.get("type"))
                    .contains(EventTypes.GIT_PR_OPENED);
        } finally {
            SecurityContextHolder.clearContext();
        }

        // With clean SecurityContext (real outbox worker thread), GIT TaskStatusChangedEvent delivers cleanly.
        events.publishEvent(new TaskStatusChangedEvent(
                fx.taskA2(), "To Do", "In Progress", TaskStatusChangedEvent.StatusChangeCause.GIT,
                fx.boardA(), fx.wsA(), fx.colsA().get("TODO"), fx.colsA().get("IN_PROGRESS"),
                "TODO", "IN_PROGRESS", UUID.randomUUID(), UUID.randomUUID(), null, BASE_TIME));
        assertThat(notificationRows(fx.assigneeA2()))
                .extracting(r -> r.get("type"))
                .containsExactly(EventTypes.TASK_STATUS_CHANGED);
    }

    @Test
    void neg11_stompAuthorizationBlocksRawQueuesForeignUserQueuesForeignBoardsAndRevokedTokenVersion()
            throws Exception {
        // Forbidden destinations on authenticated WS-A member
        for (String forbidden : List.of(
                "/queue/notifications",
                "/queue/**",
                "/user/" + fx.assigneeB() + "/queue/notifications")) {
            try (var socket = connectStomp(fx.assigneeA())) {
                socket.subscribeRawExpectingRejection(forbidden);
                assertThat(socket.awaitErrorOrDisconnect(Duration.ofSeconds(5)))
                        .as("Expected STOMP rejection for %s", forbidden).isTrue();
            }
        }

        // Foreign workspace member cannot subscribe to Board A
        try (var outsider = connectStomp(fx.assigneeB())) {
            outsider.subscribeRawExpectingRejection("/topic/boards/" + fx.boardA());
            assertThat(outsider.awaitErrorOrDisconnect(Duration.ofSeconds(5)))
                    .as("Expected STOMP rejection for foreign board").isTrue();
        }

        // Fresh user whose token_version is already 1 cannot CONNECT with stale token_version 0
        UUID revokedSessionUser = insertUser("revoked-tv", null);
        jdbc.update("UPDATE users SET token_version = 1 WHERE id = ?", revokedSessionUser);
        assertThatThrownBy(() -> {
            try (var ignored = connectStomp(revokedSessionUser, 0)) {
                // Should never connect with revoked token version.
            }
        }).isInstanceOf(Exception.class);
    }

    @Test
    void neg12_notificationRestEnforcesAntiIdorAndExcludesEmailSecretsAndRawScriptTags() throws Exception {
        assertThat(sendSignedWebhook(REPO_WS_A, SECRET_A_CURRENT, "push", UUID.randomUUID().toString(),
                pushPayload(Long.parseLong(REMOTE_REPO_A), "feature/T-001", hexSha('4', 41),
                        "fix: T-001 " + XSS_CANARY + " " + EMAIL_CANARY, BASE_TIME, "dev-xuan")).statusCode())
                .isEqualTo(204);
        gitOutbox.dispatch();

        List<Map<String, Object>> rows = notificationRows(fx.assigneeA());
        assertThat(rows).hasSize(1);
        UUID notifId = (UUID) rows.get(0).get("id");

        // Collaborator in same workspace and outsider in WS-B cannot list or mark-read Assignee A's notification.
        for (UUID other : List.of(fx.collabA(), fx.assigneeB())) {
            mvc.perform(get("/api/v1/notifications").header("Authorization", bearer(other)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(0));

            mvc.perform(patch("/api/v1/notifications/" + notifId + "/read").header("Authorization", bearer(other)))
                    .andExpect(status().isNotFound());
        }

        // Notification remains unread and free of PII/secret/XSS canary payloads.
        String body = mvc.perform(get("/api/v1/notifications").header("Authorization", bearer(fx.assigneeA())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].isRead").value(false))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(EMAIL_CANARY, SECRET_A_CURRENT, "<script>");
    }

    // -------------------------------------------------------------------------
    // Suite 3: Idempotency, Reordering, Manual Watermark, Fault Isolation & Concurrency
    // -------------------------------------------------------------------------

    @Test
    void idem01AndIdem02AndIdem03_transportReplaySemanticReplayAndDeliveryConflictAreHandledSafely()
            throws Exception {
        String deliveryId = UUID.randomUUID().toString();
        Map<String, Object> push = pushPayload(Long.parseLong(REMOTE_REPO_A_ALT), "feature/T-001",
                hexSha('5', 51), "feat: T-001 idempotency", BASE_TIME, "dev-xuan");

        // IDEM-01: Exact duplicate delivery (same X-GitHub-Delivery + same body)
        assertThat(sendSignedWebhook(REPO_WS_A_ALT, SECRET_A_ALT_CURRENT, "push", deliveryId, push).statusCode())
                .isEqualTo(204);
        gitOutbox.dispatch();
        assertThat(sendSignedWebhook(REPO_WS_A_ALT, SECRET_A_ALT_CURRENT, "push", deliveryId, push).statusCode())
                .isEqualTo(204);
        gitOutbox.dispatch();

        assertThat(countRows("github_webhook_receipts")).isEqualTo(1);
        assertThat(countRows("git_commits")).isEqualTo(1);
        assertThat(countRows("git_domain_event_outbox")).isEqualTo(1);
        assertThat(countRows("board_git_event_receipts")).isEqualTo(1);
        assertThat(countRows("board_audit_events")).isEqualTo(1);
        assertThat(notificationRows(fx.assigneeA())).hasSize(1);

        // IDEM-02: Semantic replay (new X-GitHub-Delivery, same commit SHA/message/timestamp)
        assertThat(sendSignedWebhook(REPO_WS_A_ALT, SECRET_A_ALT_CURRENT, "push", UUID.randomUUID().toString(), push)
                .statusCode()).isEqualTo(204);
        gitOutbox.dispatch();
        assertThat(countRows("git_commits")).isEqualTo(1);
        assertThat(countRows("git_domain_event_outbox")).isEqualTo(1);
        assertThat(notificationRows(fx.assigneeA())).hasSize(1);

        // IDEM-03: Same X-GitHub-Delivery with different payload -> 409 WEBHOOK_DELIVERY_CONFLICT
        Map<String, Object> conflictingPush = pushPayload(Long.parseLong(REMOTE_REPO_A_ALT), "feature/T-002",
                hexSha('5', 52), "feat: T-002 tampered delivery", BASE_TIME.plusSeconds(1), "dev-xuan");
        assertProblemResponse(
                sendSignedWebhook(REPO_WS_A_ALT, SECRET_A_ALT_CURRENT, "push", deliveryId, conflictingPush),
                409, "WEBHOOK_DELIVERY_CONFLICT");
        gitOutbox.dispatch();
        assertThat(taskRow(fx.taskA2()).get("column_id")).isEqualTo(fx.colsA().get("TODO"));
        assertThat(notificationRows(fx.assigneeA2())).isEmpty();
    }

    @Test
    void idem04_outboxLeaseExpiryReplayAfterCrashDoesNotDuplicateBoardAuditOrNotifications() throws Exception {
        assertThat(sendSignedWebhook(REPO_WS_A, SECRET_A_CURRENT, "pull_request", UUID.randomUUID().toString(),
                prPayload(Long.parseLong(REMOTE_REPO_A), "opened", 88, "T-001: Lease recovery test",
                        "feature/T-001", "develop", "open", false, BASE_TIME)).statusCode()).isEqualTo(204);

        // Simulate worker claiming and publishing, then crashing BEFORE marking PUBLISHED.
        var claimed = gitOutbox.claim();
        assertThat(claimed).hasSize(1);
        var entry = claimed.get(0);
        events.publishEvent(new GitPrOpenedEvent(
                entry.taskId(), entry.url(), entry.repo(), null, entry.occurredAt(),
                entry.id(), entry.repositoryId(), entry.workspaceId()));

        assertThat(taskRow(fx.taskA1()).get("column_id")).isEqualTo(fx.colsA().get("IN_REVIEW"));
        assertThat(notificationRows(fx.assigneeA())).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT status FROM git_domain_event_outbox WHERE id = ?",
                String.class, entry.id())).isEqualTo("PROCESSING");

        // Lease expires; restarted worker dispatches the exact same immutable event again.
        jdbc.update("UPDATE git_domain_event_outbox SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second'");
        gitOutbox.dispatch();

        assertThat(jdbc.queryForObject("SELECT status FROM git_domain_event_outbox WHERE id = ?",
                String.class, entry.id())).isEqualTo("PUBLISHED");
        assertThat(taskRow(fx.taskA1()).get("column_id")).isEqualTo(fx.colsA().get("IN_REVIEW"));
        assertThat(countRows("board_git_event_receipts")).isEqualTo(1);
        assertThat(countRows("board_audit_events")).isEqualTo(1);
        // Notification deduplicated both task.status_changed (Board DUPLICATE didn't re-emit) and git.pr_opened (ON CONFLICT).
        assertThat(notificationRows(fx.assigneeA())).hasSize(2);
    }

    @Test
    void idem05_outOfOrderDeliveryMergeBeforePushNeverMovesTaskBackwardOrEmitsExtraStatusNotifications()
            throws Exception {
        // 1. PR merged arrives first -> DONE
        assertThat(sendSignedWebhook(REPO_WS_A, SECRET_A_CURRENT, "pull_request", UUID.randomUUID().toString(),
                prPayload(Long.parseLong(REMOTE_REPO_A), "closed", 91, "T-001: Out of order merge",
                        "feature/T-001", "develop", "closed", true, BASE_TIME.plusSeconds(20))).statusCode())
                .isEqualTo(204);
        gitOutbox.dispatch();
        assertThat(taskRow(fx.taskA1()).get("column_id")).isEqualTo(fx.colsA().get("DONE"));
        assertThat(notificationRows(fx.assigneeA()))
                .extracting(r -> r.get("type"))
                .containsExactlyInAnyOrder(EventTypes.TASK_STATUS_CHANGED, EventTypes.GIT_PR_MERGED);

        // 2. Older push arrives later -> NOOP (stays DONE, 0 new status notifications)
        assertThat(sendSignedWebhook(REPO_WS_A, SECRET_A_CURRENT, "push", UUID.randomUUID().toString(),
                pushPayload(Long.parseLong(REMOTE_REPO_A), "feature/T-001", hexSha('6', 61),
                        "feat: T-001 delayed push", BASE_TIME, "dev-xuan")).statusCode()).isEqualTo(204);
        gitOutbox.dispatch();
        assertThat(taskRow(fx.taskA1()).get("column_id")).isEqualTo(fx.colsA().get("DONE"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE recipient_id = ? AND type = ?",
                Long.class, fx.assigneeA(), EventTypes.TASK_STATUS_CHANGED)).isEqualTo(1);

        // 3. Another PR opened arrives later -> NOOP on Board (stays DONE, still only 1 task.status_changed)
        assertThat(sendSignedWebhook(REPO_WS_A, SECRET_A_CURRENT, "pull_request", UUID.randomUUID().toString(),
                prPayload(Long.parseLong(REMOTE_REPO_A), "opened", 92, "T-001: Delayed PR open",
                        "feature/T-001", "develop", "open", false, BASE_TIME.plusSeconds(10))).statusCode())
                .isEqualTo(204);
        gitOutbox.dispatch();
        assertThat(taskRow(fx.taskA1()).get("column_id")).isEqualTo(fx.colsA().get("DONE"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE recipient_id = ? AND type = ?",
                Long.class, fx.assigneeA(), EventTypes.TASK_STATUS_CHANGED)).isEqualTo(1);
    }

    @Test
    void idem06_manualReopenWatermarkBlocksStaleGitEventsAndAllowsNewerCommitToAdvance() throws Exception {
        // Git PR merged moves T-001 -> DONE
        assertThat(sendSignedWebhook(REPO_WS_A_ALT, SECRET_A_ALT_CURRENT, "pull_request", UUID.randomUUID().toString(),
                prPayload(Long.parseLong(REMOTE_REPO_A_ALT), "closed", 95, "T-001: Merge before reopen",
                        "feature/T-001", "develop", "closed", true, BASE_TIME)).statusCode()).isEqualTo(204);
        gitOutbox.dispatch();
        assertThat(taskRow(fx.taskA1()).get("column_id")).isEqualTo(fx.colsA().get("DONE"));

        // Owner A manually reopens T-001 back to TODO (sets last_manual_status_changed_at = Tm)
        mvc.perform(patch("/api/v1/tasks/" + fx.taskA1() + "/move")
                        .header("Authorization", bearer(fx.ownerA()))
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("columnId", fx.colsA().get("TODO"), "position", 0))))
                .andExpect(status().isOk());

        Instant watermark = ((Timestamp) taskRow(fx.taskA1()).get("last_manual_status_changed_at")).toInstant();
        int statusNotifsBeforeStale = jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE recipient_id = ? AND type = ?",
                Integer.class, fx.assigneeA(), EventTypes.TASK_STATUS_CHANGED);

        // Stale push at exactly watermark is blocked (STALE_MANUAL) and emits 0 notifications
        assertThat(sendSignedWebhook(REPO_WS_A_ALT, SECRET_A_ALT_CURRENT, "push", UUID.randomUUID().toString(),
                pushPayload(Long.parseLong(REMOTE_REPO_A_ALT), "feature/T-001", hexSha('7', 71),
                        "feat: T-001 stale at watermark", watermark, "dev-xuan")).statusCode()).isEqualTo(204);
        gitOutbox.dispatch();

        assertThat(taskRow(fx.taskA1()).get("column_id")).isEqualTo(fx.colsA().get("TODO"));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM board_git_event_receipts WHERE outcome = 'STALE_MANUAL'", Long.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE recipient_id = ? AND type = ?",
                Integer.class, fx.assigneeA(), EventTypes.TASK_STATUS_CHANGED)).isEqualTo(statusNotifsBeforeStale);

        // Fresh push after watermark advances T-001 -> IN_PROGRESS and creates 1 new notification
        assertThat(sendSignedWebhook(REPO_WS_A_ALT, SECRET_A_ALT_CURRENT, "push", UUID.randomUUID().toString(),
                pushPayload(Long.parseLong(REMOTE_REPO_A_ALT), "feature/T-001", hexSha('7', 72),
                        "feat: T-001 fresh after reopen", watermark.plusSeconds(5), "dev-xuan")).statusCode())
                .isEqualTo(204);
        gitOutbox.dispatch();

        assertThat(taskRow(fx.taskA1()).get("column_id")).isEqualTo(fx.colsA().get("IN_PROGRESS"));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE recipient_id = ? AND type = ?",
                Integer.class, fx.assigneeA(), EventTypes.TASK_STATUS_CHANGED))
                .isEqualTo(statusNotifsBeforeStale + 1);
    }

    @Test
    void idem07_boardTargetFullRollsBackWithoutPhantomNotificationsAndRecoversOnRetry() throws Exception {
        // Fill IN_PROGRESS up to max-per-column=5
        insertTask(fx.colsA().get("IN_PROGRESS"), "Filler 1", 0, null);
        insertTask(fx.colsA().get("IN_PROGRESS"), "Filler 2", 1, null);
        insertTask(fx.colsA().get("IN_PROGRESS"), "Filler 3", 2, null);
        insertTask(fx.colsA().get("IN_PROGRESS"), "Filler 4", 3, null);
        UUID fillerLast = insertTask(fx.colsA().get("IN_PROGRESS"), "Filler 5", 4, null);

        assertThat(sendSignedWebhook(REPO_WS_A, SECRET_A_CURRENT, "push", UUID.randomUUID().toString(),
                pushPayload(Long.parseLong(REMOTE_REPO_A), "feature/T-001", hexSha('8', 81),
                        "feat: T-001 blocked by full column", BASE_TIME, "dev-xuan")).statusCode()).isEqualTo(204);
        gitOutbox.dispatch();

        // Attempt 1 fails transiently: rolled back cleanly, 0 phantom notifications, outbox queued for retry.
        assertThat(taskRow(fx.taskA1()).get("column_id")).isEqualTo(fx.colsA().get("TODO"));
        assertThat(countRows("board_git_event_receipts")).isZero();
        assertThat(countRows("board_audit_events")).isZero();
        assertThat(countRows("notifications")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM git_domain_event_outbox", String.class))
                .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT attempts FROM git_domain_event_outbox", Integer.class))
                .isEqualTo(1);

        // Free one slot and force retry due immediately.
        jdbc.update("DELETE FROM tasks WHERE id = ?", fillerLast);
        jdbc.update("UPDATE git_domain_event_outbox SET next_attempt_at = CURRENT_TIMESTAMP - INTERVAL '1 second'");
        gitOutbox.dispatch();

        assertThat(taskRow(fx.taskA1()).get("column_id")).isEqualTo(fx.colsA().get("IN_PROGRESS"));
        assertThat(jdbc.queryForObject("SELECT status FROM git_domain_event_outbox", String.class))
                .isEqualTo("PUBLISHED");
        assertThat(countRows("board_git_event_receipts")).isEqualTo(1);
        assertThat(notificationRows(fx.assigneeA())).hasSize(1);
        assertThat(notificationRows(fx.assigneeA()).get(0).get("type")).isEqualTo(EventTypes.TASK_STATUS_CHANGED);
    }

    @Test
    void idem08_notificationConsumerFailureIsIsolatedAndDoesNotRollbackBoardOrPoisonOutbox() throws Exception {
        double failBefore = metrics.counter("devflow.task.events.consume",
                "eventType", EventTypes.TASK_STATUS_CHANGED, "consumer", "notification", "outcome", "failure").count();

        jdbc.execute("""
                CREATE FUNCTION t041_notif_fail() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'synthetic notification outage'; END $$
                """);
        jdbc.execute("""
                CREATE TRIGGER t041_notif_fail BEFORE INSERT ON notifications
                FOR EACH ROW EXECUTE FUNCTION t041_notif_fail()
                """);
        try {
            assertThat(sendSignedWebhook(REPO_WS_A, SECRET_A_CURRENT, "push", UUID.randomUUID().toString(),
                    pushPayload(Long.parseLong(REMOTE_REPO_A), "feature/T-001", hexSha('9', 91),
                            "feat: T-001 survive notif outage", BASE_TIME, "dev-xuan")).statusCode()).isEqualTo(204);
            gitOutbox.dispatch();

            // Board transition and outbox completion are unaffected.
            assertThat(taskRow(fx.taskA1()).get("column_id")).isEqualTo(fx.colsA().get("IN_PROGRESS"));
            assertThat(countRows("board_git_event_receipts")).isEqualTo(1);
            assertThat(countRows("board_audit_events")).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT status FROM git_domain_event_outbox", String.class))
                    .isEqualTo("PUBLISHED");
            assertThat(countRows("notifications")).isZero();
            assertThat(metrics.counter("devflow.task.events.consume",
                    "eventType", EventTypes.TASK_STATUS_CHANGED, "consumer", "notification", "outcome", "failure").count())
                    .isEqualTo(failBefore + 1);
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS t041_notif_fail ON notifications");
            jdbc.execute("DROP FUNCTION IF EXISTS t041_notif_fail()");
        }

        // Subsequent event after recovery persists notifications normally.
        assertThat(sendSignedWebhook(REPO_WS_A, SECRET_A_CURRENT, "pull_request", UUID.randomUUID().toString(),
                prPayload(Long.parseLong(REMOTE_REPO_A), "opened", 99, "T-001: Post-recovery PR",
                        "feature/T-001", "develop", "open", false, BASE_TIME.plusSeconds(10))).statusCode())
                .isEqualTo(204);
        gitOutbox.dispatch();
        assertThat(taskRow(fx.taskA1()).get("column_id")).isEqualTo(fx.colsA().get("IN_REVIEW"));
        assertThat(notificationRows(fx.assigneeA())).hasSize(2);
    }

    @Test
    void idem09_webhookTransactionRollbackBeforeCommitLeavesZeroStateAndAllowsCleanRetry() throws Exception {
        String deliveryId = UUID.randomUUID().toString();
        Map<String, Object> payload = pushPayload(Long.parseLong(REMOTE_REPO_A_ALT), "feature/T-001",
                hexSha('a', 901), "feat: T-001 rollback test", BASE_TIME, "dev-xuan");

        jdbc.execute("""
                CREATE FUNCTION t041_outbox_fail() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'synthetic outbox insert failure'; END $$
                """);
        jdbc.execute("""
                CREATE TRIGGER t041_outbox_fail BEFORE INSERT ON git_domain_event_outbox
                FOR EACH ROW EXECUTE FUNCTION t041_outbox_fail()
                """);
        try {
            var failed = sendSignedWebhook(REPO_WS_A_ALT, SECRET_A_ALT_CURRENT, "push", deliveryId, payload);
            assertProblemResponse(failed, 503, "WEBHOOK_UNAVAILABLE");

            assertThat(countRows("github_webhook_receipts")).isZero();
            assertThat(countRows("git_commits")).isZero();
            assertThat(countRows("git_commit_task_links")).isZero();
            assertThat(countRows("git_domain_event_outbox")).isZero();
            assertThat(taskRow(fx.taskA1()).get("column_id")).isEqualTo(fx.colsA().get("TODO"));
            assertThat(countRows("notifications")).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS t041_outbox_fail ON git_domain_event_outbox");
            jdbc.execute("DROP FUNCTION IF EXISTS t041_outbox_fail()");
        }

        // Redelivering the exact same webhook after DB recovers succeeds end-to-end.
        assertThat(sendSignedWebhook(REPO_WS_A_ALT, SECRET_A_ALT_CURRENT, "push", deliveryId, payload).statusCode())
                .isEqualTo(204);
        gitOutbox.dispatch();
        assertThat(taskRow(fx.taskA1()).get("column_id")).isEqualTo(fx.colsA().get("IN_PROGRESS"));
        assertThat(notificationRows(fx.assigneeA())).hasSize(1);
    }

    @Test
    void idem10_concurrentDuplicateAndDistinctGitEventsRemainIdempotentAcrossBoardAndNotifications()
            throws Exception {
        UUID commitEventId = UUID.randomUUID();
        UUID prEventId = UUID.randomUUID();
        DevFlowEvent commitEvent = new GitCommitLinkedEvent(
                fx.taskA1(), hexSha('b', 999), "devflow-test/app-a", "dev-xuan",
                BASE_TIME, commitEventId, REPO_WS_A, fx.wsA());
        DevFlowEvent prEvent = new GitPrOpenedEvent(
                fx.taskA1(), "https://github.com/devflow-test/app-a/pull/100", "devflow-test/app-a",
                null, BASE_TIME.plusSeconds(1), prEventId, REPO_WS_A, fx.wsA());

        CountDownLatch gate = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<Boolean>> futures = Stream.of(commitEvent, commitEvent, commitEvent, prEvent, prEvent, prEvent)
                    .map(evt -> pool.submit(() -> {
                        gate.await();
                        SecurityContextHolder.clearContext();
                        events.publishEvent(evt);
                        return true;
                    }))
                    .toList();
            gate.countDown();
            for (Future<Boolean> future : futures) {
                assertThat(future.get(15, TimeUnit.SECONDS)).isTrue();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(taskRow(fx.taskA1()).get("column_id")).isEqualTo(fx.colsA().get("IN_REVIEW"));
        assertThat(countRows("board_git_event_receipts")).isEqualTo(2);

        // Exactly 1 git.pr_opened notification (deduplicated by unique index) and 1..2 task.status_changed depending on race order.
        List<Map<String, Object>> notifs = notificationRows(fx.assigneeA());
        long prNotifs = notifs.stream().filter(r -> EventTypes.GIT_PR_OPENED.equals(r.get("type"))).count();
        long statusNotifs = notifs.stream().filter(r -> EventTypes.TASK_STATUS_CHANGED.equals(r.get("type"))).count();
        assertThat(prNotifs).isEqualTo(1);
        assertThat(statusNotifs).isBetween(1L, 2L);

        // Column positions remain strictly contiguous 0..N-1.
        for (UUID colId : fx.colsA().values()) {
            List<Integer> positions = jdbc.queryForList(
                    "SELECT position FROM tasks WHERE column_id = ? ORDER BY position", Integer.class, colId);
            List<Integer> expected = new ArrayList<>();
            for (int i = 0; i < positions.size(); i++) {
                expected.add(i);
            }
            assertThat(positions).containsExactlyElementsOf(expected);
        }
    }

    // -------------------------------------------------------------------------
    // Suite 6 Operational Checks: ARCH-05 (/actuator/prometheus) & ARCH-06 (/actuator/health)
    // -------------------------------------------------------------------------

    @Test
    void arch05AndArch06_actuatorHealthIsUpAndPrometheusExportsLowCardinalityCrossModuleMetrics()
            throws Exception {
        // Trigger one full chain so all cross-module meters are populated.
        assertThat(sendSignedWebhook(REPO_WS_A, SECRET_A_CURRENT, "push", UUID.randomUUID().toString(),
                pushPayload(Long.parseLong(REMOTE_REPO_A), "feature/T-001", hexSha('c', 777),
                        "feat: T-001 observability", BASE_TIME, "dev-xuan")).statusCode()).isEqualTo(204);
        gitOutbox.dispatch();

        // ARCH-06: /actuator/health
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.db.status").value("UP"))
                .andExpect(jsonPath("$.components.aiProvider.status").value("UP"));

        // ARCH-05: /actuator/prometheus & tag cardinality safety
        String scrape = mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(scrape)
                .contains("devflow_gitci_webhook_requests_total")
                .contains("devflow_gitci_outbox_events_total")
                .contains("devflow_board_git_transitions_total")
                .contains("devflow_task_events_dispatch_total")
                .contains("devflow_task_events_consume_total")
                .contains("devflow_notification_delivered_total");

        Set<String> forbiddenTagKeys = Set.of("workspaceId", "taskId", "userId", "recipientId", "sha", "deliveryId", "repo");
        for (Meter meter : metrics.getMeters()) {
            if (!meter.getId().getName().startsWith("devflow.")) {
                continue;
            }
            for (var tag : meter.getId().getTags()) {
                assertThat(forbiddenTagKeys).doesNotContain(tag.getKey());
                assertThat(tag.getValue()).doesNotContain(
                        fx.wsA().toString(), fx.taskA1().toString(), fx.assigneeA().toString(),
                        SECRET_A_CURRENT, EMAIL_CANARY);
            }
        }
    }

    private void assertProblemResponse(java.net.http.HttpResponse<String> response, int expectedStatus, String expectedCode)
            throws Exception {
        assertThat(response.statusCode()).isEqualTo(expectedStatus);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("status").asInt()).isEqualTo(expectedStatus);
        assertThat(body.path("code").asText()).isEqualTo(expectedCode);
        assertThat(body.path("instance").asText()).isEqualTo("/api/v1/gitci/webhook/github");
        assertThat(response.body()).doesNotContain("java.lang.", "Exception", "org.springframework.", SECRET_A_CURRENT);
    }
}
