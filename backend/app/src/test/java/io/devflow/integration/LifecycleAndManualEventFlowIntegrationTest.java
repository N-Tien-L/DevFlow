package io.devflow.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import io.devflow.common.event.DeletedUser;
import io.devflow.common.event.EventTypes;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MvcResult;

/**
 * T-041 Suite 5 ({@code LIFE-01..06}): validates manual Board CRUD event fan-out
 * ({@code task.created}, {@code task.status_changed}) and cross-module lifecycle cleanup
 * ({@code task.references_removed}, {@code user.account_deleted}, {@code workspace.deleted})
 * on real PostgreSQL 17.
 *
 * <p>Intentionally shares identical {@link SpringBootTest} properties with
 * {@link GitBoardNotificationFlowIntegrationTest} so Spring TestContext caches one shared context.
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
class LifecycleAndManualEventFlowIntegrationTest extends MultiModulePostgresSupport {

    private TenantFixture fx;

    @BeforeEach
    void setupFixture() {
        fx = seedStandardFixture();
    }

    @Test
    void life01AndLife02_manualTaskCreationNotifiesAssigneeAndSelfActionsOrSameColumnReorderEmitZeroNotifications()
            throws Exception {
        double aiTaskCreatedBefore = metrics.counter("devflow.task.events.consume",
                "eventType", EventTypes.TASK_CREATED, "consumer", "ai", "outcome", "success").count();

        // LIFE-01: Owner A creates a task assigned to Assignee A (with live STOMP subscriber)
        try (var assigneeSocket = connectStomp(fx.assigneeA())) {
            assigneeSocket.subscribeNotifications();

            // Clear column TODO down to 2 items so max-per-column=3 allows creating 1 more task in TODO.
            jdbc.update("DELETE FROM tasks WHERE id IN (?, ?)", fx.taskUnassigned(), fx.taskRevoked());

            MvcResult created = mvc.perform(post("/api/v1/columns/" + fx.colsA().get("TODO") + "/tasks")
                            .header("Authorization", bearer(fx.ownerA()))
                            .contentType("application/json")
                            .content(json.writeValueAsString(Map.of(
                                    "title", "Manual Assigned Task",
                                    "description", "Assigned by Owner A",
                                    "priority", "HIGH",
                                    "assigneeId", fx.assigneeA().toString()))))
                    .andExpect(status().isCreated())
                    .andReturn();
            UUID newTaskId = UUID.fromString(json.readTree(created.getResponse().getContentAsString()).path("id").asText());

            assertThat(notificationRows(fx.assigneeA())).hasSize(1);
            assertThat(notificationRows(fx.assigneeA()).get(0))
                    .containsEntry("type", EventTypes.TASK_CREATED)
                    .containsEntry("workspace_id", fx.wsA())
                    .containsEntry("reference_id", newTaskId);
            assertThat(notificationRows(fx.ownerA())).isEmpty();

            Map<?, ?> frame = assigneeSocket.pollNotificationFrame(Duration.ofSeconds(5));
            assertThat(frame).isNotNull();
            assertThat(frame.get("type")).isEqualTo(EventTypes.TASK_CREATED);
            assertThat(frame.get("referenceId")).isEqualTo(newTaskId.toString());

            assertThat(metrics.counter("devflow.task.events.consume",
                    "eventType", EventTypes.TASK_CREATED, "consumer", "ai", "outcome", "success").count())
                    .isEqualTo(aiTaskCreatedBefore + 1);

            // LIFE-02a: Assignee A creates a task assigned to themselves -> 0 new notifications
            MvcResult selfCreated = mvc.perform(post("/api/v1/columns/" + fx.colsA().get("IN_PROGRESS") + "/tasks")
                            .header("Authorization", bearer(fx.assigneeA()))
                            .contentType("application/json")
                            .content(json.writeValueAsString(Map.of(
                                    "title", "Self Assigned Task",
                                    "assigneeId", fx.assigneeA().toString()))))
                    .andExpect(status().isCreated())
                    .andReturn();
            UUID selfTaskId = UUID.fromString(json.readTree(selfCreated.getResponse().getContentAsString()).path("id").asText());

            // LIFE-02b: Assignee A moves their own task to IN_REVIEW -> 0 new notifications
            mvc.perform(patch("/api/v1/tasks/" + selfTaskId + "/move")
                            .header("Authorization", bearer(fx.assigneeA()))
                            .contentType("application/json")
                            .content(json.writeValueAsString(Map.of("columnId", fx.colsA().get("IN_REVIEW"), "position", 0))))
                    .andExpect(status().isOk());

            // LIFE-02c: Collab A reorders Task A1 within the same TODO column -> 0 status change notifications
            mvc.perform(patch("/api/v1/tasks/" + fx.taskA1() + "/move")
                            .header("Authorization", bearer(fx.collabA()))
                            .contentType("application/json")
                            .content(json.writeValueAsString(Map.of("columnId", fx.colsA().get("TODO"), "position", 1))))
                    .andExpect(status().isOk());

            assertThat(notificationRows(fx.assigneeA())).hasSize(1);
            assertThat(notificationRows(fx.collabA())).isEmpty();
            assertThat(assigneeSocket.pollNotificationFrame(Duration.ofMillis(300))).isNull();
        }
    }

    @Test
    void life03_collaboratorManualMoveNotifiesAssigneeUpdatesWatermarkAndBroadcastsBoardStomp() throws Exception {
        try (var assigneeSocket = connectStomp(fx.assigneeA());
             var collabSocket = connectStomp(fx.collabA())) {
            assigneeSocket.subscribeBoard(fx.boardA());
            assigneeSocket.subscribeNotifications();
            collabSocket.subscribeBoard(fx.boardA());
            collabSocket.subscribeNotifications();

            mvc.perform(patch("/api/v1/tasks/" + fx.taskA1() + "/move")
                            .header("Authorization", bearer(fx.collabA()))
                            .contentType("application/json")
                            .content(json.writeValueAsString(Map.of(
                                    "columnId", fx.colsA().get("IN_REVIEW"),
                                    "position", 0))))
                    .andExpect(status().isOk());

            assertThat(taskRow(fx.taskA1()).get("column_id")).isEqualTo(fx.colsA().get("IN_REVIEW"));
            assertThat(taskRow(fx.taskA1()).get("last_manual_status_changed_at")).isNotNull();

            // Notification persisted ONLY for Assignee A (not Collab A)
            assertThat(notificationRows(fx.assigneeA())).hasSize(1);
            assertThat(notificationRows(fx.assigneeA()).get(0))
                    .containsEntry("type", EventTypes.TASK_STATUS_CHANGED)
                    .containsEntry("reference_id", fx.taskA1());
            assertThat(notificationRows(fx.collabA())).isEmpty();

            // Both see CARD_MOVED with actorId=collabA; only Assignee A gets personal notification frame.
            for (var socket : List.of(assigneeSocket, collabSocket)) {
                Map<?, ?> frame = socket.pollBoardFrame(Duration.ofSeconds(5));
                assertThat(frame).isNotNull();
                assertThat(frame.get("type")).isEqualTo("CARD_MOVED");
                assertThat(frame.get("actorId")).isEqualTo(fx.collabA().toString());
            }
            Map<?, ?> notifFrame = assigneeSocket.pollNotificationFrame(Duration.ofSeconds(5));
            assertThat(notifFrame).isNotNull();
            assertThat(notifFrame.get("type")).isEqualTo(EventTypes.TASK_STATUS_CHANGED);
            assertThat(collabSocket.pollNotificationFrame(Duration.ofMillis(300))).isNull();
        }
    }

    @Test
    void life04_deletingTaskCleansGitLinksAndPendingOutboxWhilePreservingSharedCommitLink() throws Exception {
        // One commit linked to BOTH T-001 and T-002 + PR linked to T-001 (keep outbox PENDING before deletion).
        assertThat(sendSignedWebhook(REPO_WS_A, SECRET_A_CURRENT, "push", UUID.randomUUID().toString(),
                pushPayload(Long.parseLong(REMOTE_REPO_A), "feature/shared", hexSha('f', 1401),
                        "fix: shared commit for T-001 and T-002", BASE_TIME, "dev-xuan")).statusCode())
                .isEqualTo(204);
        assertThat(sendSignedWebhook(REPO_WS_A, SECRET_A_CURRENT, "pull_request", UUID.randomUUID().toString(),
                prPayload(Long.parseLong(REMOTE_REPO_A), "opened", 141, "T-001 PR to clean",
                        "feature/T-001", "develop", "open", false, BASE_TIME.plusSeconds(5))).statusCode())
                .isEqualTo(204);

        assertThat(countRows("git_commit_task_links")).isEqualTo(2);
        assertThat(countRows("git_pull_request_task_links")).isEqualTo(1);
        assertThat(countRows("git_domain_event_outbox")).isEqualTo(3);

        // Delete Task A1 via real Board REST endpoint -> publishes TaskReferencesRemovedEvent synchronously in tx.
        mvc.perform(delete("/api/v1/tasks/" + fx.taskA1()).header("Authorization", bearer(fx.ownerA())))
                .andExpect(status().isNoContent());

        // Task A1's commit link, PR link, and PENDING outbox entries are gone; Task A2's link & scalar mirror survive.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM git_commit_task_links WHERE task_id = ?", Long.class, fx.taskA1())).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM git_pull_request_task_links WHERE task_id = ?", Long.class, fx.taskA1())).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM git_domain_event_outbox WHERE task_id = ?", Long.class, fx.taskA1())).isZero();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM git_commit_task_links WHERE task_id = ?", Long.class, fx.taskA2())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT task_id FROM git_commits", UUID.class)).isEqualTo(fx.taskA2());

        // Dispatch remaining outbox -> cleanly transitions Task A2 and notifies Assignee A2 with zero errors.
        gitOutbox.dispatch();
        assertThat(taskRow(fx.taskA2()).get("column_id")).isEqualTo(fx.colsA().get("IN_PROGRESS"));
        assertThat(notificationRows(fx.assigneeA2())).hasSize(1);
        assertThat(notificationRows(fx.assigneeA())).isEmpty();
    }

    @Test
    void life05AndLife06_accountDeletionAndSoleWorkspaceDeletionCleanUpBoardGitCiAndNotifications()
            throws Exception {
        String password = "Passw0rd!T041Delete";
        UUID memberToErase = insertUser("gdpr-member", password);
        addMember(fx.wsA(), memberToErase, "MEMBER");

        // Assign Task A1 to memberToErase, post a comment, trigger an audit event & create notifications for them.
        jdbc.update("UPDATE tasks SET assignee_id = ? WHERE id = ?", memberToErase, fx.taskA1());
        mvc.perform(post("/api/v1/tasks/" + fx.taskA1() + "/comments")
                        .header("Authorization", bearer(memberToErase))
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("content", "Team knowledge preserved after erasure"))))
                .andExpect(status().isCreated());
        mvc.perform(patch("/api/v1/tasks/" + fx.taskA1() + "/move")
                        .header("Authorization", bearer(fx.ownerA()))
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("columnId", fx.colsA().get("IN_PROGRESS"), "position", 0))))
                .andExpect(status().isOk());
        assertThat(notificationRows(memberToErase)).hasSize(1);

        // LIFE-05: Erase memberToErase via real Auth REST API DELETE /api/v1/auth/me
        mvc.perform(delete("/api/v1/auth/me")
                        .header("Authorization", bearer(memberToErase))
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("password", password))))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE id = ?", Long.class, memberToErase)).isZero();
        assertThat(notificationRows(memberToErase)).isEmpty();
        assertThat(taskRow(fx.taskA1()).get("assignee_id")).isNull();
        List<UUID> commentAuthors = jdbc.queryForList(
                "SELECT author_id FROM task_comments WHERE task_id = ?", UUID.class, fx.taskA1());
        assertThat(commentAuthors).containsExactly(DeletedUser.ID);
        assertThat(jdbc.queryForObject(
                "SELECT content FROM task_comments WHERE task_id = ?", String.class, fx.taskA1()))
                .isEqualTo("Team knowledge preserved after erasure");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM board_account_cleanup_jobs", Long.class)).isZero();

        // LIFE-06: Sole-owned workspace deletion cascades GitCI repositories + commits/PRs/CI AND deletes Board data,
        // leaving Workspace A completely untouched.
        UUID soloOwner = insertUser("solo-owner", password);
        UUID wsSolo = insertWorkspace(soloOwner, "Solo Workspace");
        addMember(wsSolo, soloOwner, "OWNER");
        UUID boardSolo = insertBoard(wsSolo, "Solo Board", false);
        Map<String, UUID> colsSolo = insertStandardColumns(boardSolo);
        UUID taskSolo = insertTask(colsSolo.get("TODO"), "Solo Task", 0, soloOwner);
        UUID repoSolo = UUID.randomUUID();
        insertRepository(repoSolo, wsSolo, "49999", "devflow-test/solo",
                "https://github.com/devflow-test/solo", "test:t041:solo");
        UUID commitSolo = jdbc.queryForObject("""
                INSERT INTO git_commits(repository_id, sha, message, committed_at, task_id)
                VALUES (?, ?, 'T-001 solo', CURRENT_TIMESTAMP, ?) RETURNING id
                """, UUID.class, repoSolo, hexSha('9', 1601), taskSolo);
        jdbc.update("INSERT INTO git_commit_task_links(commit_id, task_id, source_kind) VALUES (?, ?, 'MESSAGE')",
                commitSolo, taskSolo);
        UUID pipeSolo = jdbc.queryForObject("""
                INSERT INTO ci_pipelines(repository_id, pipeline_id, run_attempt, commit_sha, status)
                VALUES (?, '9901', 1, ?, 'FAILED') RETURNING id
                """, UUID.class, repoSolo, hexSha('9', 1601));
        UUID failSolo = jdbc.queryForObject("""
                INSERT INTO ci_failures(pipeline_id, failure_key, stage_name, job_name, log_status)
                VALUES (?, 'workflow-run', 'workflow', 'aggregate', 'PENDING') RETURNING id
                """, UUID.class, pipeSolo);
        jdbc.update("INSERT INTO ci_log_fetch_jobs(pipeline_id, failure_id) VALUES (?, ?)", pipeSolo, failSolo);

        // Delete soloOwner -> Auth deletes wsSolo + publishes WorkspaceDeletedEvent & UserAccountDeletedEvent.
        mvc.perform(delete("/api/v1/auth/me")
                        .header("Authorization", bearer(soloOwner))
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("password", password))))
                .andExpect(status().isNoContent());

        // All GitCI & Board rows of wsSolo are completely gone.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspaces WHERE id = ?", Long.class, wsSolo)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM repositories WHERE workspace_id = ?", Long.class, wsSolo)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM git_commits WHERE id = ?", Long.class, commitSolo)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ci_pipelines WHERE id = ?", Long.class, pipeSolo)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ci_log_fetch_jobs WHERE pipeline_id = ?", Long.class, pipeSolo)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM boards WHERE workspace_id = ?", Long.class, wsSolo)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tasks WHERE id = ?", Long.class, taskSolo)).isZero();

        // Workspace A remains 100% intact.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM boards WHERE id = ?", Long.class, fx.boardA())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM repositories WHERE id = ?", Long.class, REPO_WS_A)).isEqualTo(1);
    }
}
