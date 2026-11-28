package io.devflow.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.devflow.common.event.EventTypes;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * T-041 {@code E2E-07}: verifies the shipped production default
 * ({@code devflow.board.git.auto-transition-enabled=false}) across {@code gitci}, {@code board},
 * and {@code notification} on PostgreSQL 17.
 */
@AutoConfigureMockMvc
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "devflow.gitci.outbox.worker-enabled=false",
                "devflow.gitci.ci.fetch-enabled=false",
                "devflow.gitci.ci.publish-enabled=false"
        })
class GitBoardNotificationDefaultFlagIntegrationTest extends MultiModulePostgresSupport {

    private TenantFixture fx;

    @BeforeEach
    void setupFixture() {
        fx = seedStandardFixture();
    }

    @Test
    void e2e07_defaultDisabledAutoTransitionKeepsBoardStaticWhileDeliveringPrNotificationsCleanly()
            throws Exception {
        double commitDisabledBefore = metrics.counter("devflow.board.git.transitions",
                "eventType", EventTypes.GIT_COMMIT_LINKED, "outcome", "SKIPPED", "reason", "DISABLED").count();
        double prOpenDisabledBefore = metrics.counter("devflow.board.git.transitions",
                "eventType", EventTypes.GIT_PR_OPENED, "outcome", "SKIPPED", "reason", "DISABLED").count();
        double prMergeDisabledBefore = metrics.counter("devflow.board.git.transitions",
                "eventType", EventTypes.GIT_PR_MERGED, "outcome", "SKIPPED", "reason", "DISABLED").count();

        // Send push, PR opened, and PR merged for T-001 (assigned to Assignee A, sitting in TODO).
        assertThat(sendSignedWebhook(REPO_WS_A, SECRET_A_CURRENT, "push", UUID.randomUUID().toString(),
                pushPayload(Long.parseLong(REMOTE_REPO_A), "feature/T-001", hexSha('a', 701),
                        "feat: T-001 default flag push", BASE_TIME, "dev-xuan")).statusCode()).isEqualTo(204);
        assertThat(sendSignedWebhook(REPO_WS_A, SECRET_A_CURRENT, "pull_request", UUID.randomUUID().toString(),
                prPayload(Long.parseLong(REMOTE_REPO_A), "opened", 71, "T-001 default flag PR",
                        "feature/T-001", "develop", "open", false, BASE_TIME.plusSeconds(10))).statusCode())
                .isEqualTo(204);
        assertThat(sendSignedWebhook(REPO_WS_A, SECRET_A_CURRENT, "pull_request", UUID.randomUUID().toString(),
                prPayload(Long.parseLong(REMOTE_REPO_A), "closed", 71, "T-001 default flag PR",
                        "feature/T-001", "develop", "closed", true, BASE_TIME.plusSeconds(20))).statusCode())
                .isEqualTo(204);

        gitOutbox.dispatch();

        // Outbox rows all PUBLISHED cleanly in 1 attempt (never poisoned or retried).
        assertThat(jdbc.queryForList("SELECT status FROM git_domain_event_outbox", String.class))
                .containsExactly("PUBLISHED", "PUBLISHED", "PUBLISHED");
        assertThat(jdbc.queryForList("SELECT attempts FROM git_domain_event_outbox", Integer.class))
                .containsExactly(1, 1, 1);

        // Board stays completely unchanged (TODO, version 0, 0 receipts, 0 audit rows).
        assertThat(taskRow(fx.taskA1()).get("column_id")).isEqualTo(fx.colsA().get("TODO"));
        assertThat(taskRow(fx.taskA1()).get("version")).isEqualTo(0L);
        assertThat(countRows("board_git_event_receipts")).isZero();
        assertThat(countRows("board_audit_events")).isZero();

        // Notification delivers ONLY the two direct PR notifications (0 task.status_changed).
        List<String> notifTypes = jdbc.queryForList(
                "SELECT type FROM notifications WHERE recipient_id = ? AND reference_id = ? ORDER BY type",
                String.class, fx.assigneeA(), fx.taskA1());
        assertThat(notifTypes).containsExactly(EventTypes.GIT_PR_MERGED, EventTypes.GIT_PR_OPENED);
        assertThat(notificationRows(fx.collabA())).isEmpty();

        mvc.perform(get("/api/v1/notifications/unread-count").header("Authorization", bearer(fx.assigneeA())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2));

        assertThat(metrics.counter("devflow.board.git.transitions",
                "eventType", EventTypes.GIT_COMMIT_LINKED, "outcome", "SKIPPED", "reason", "DISABLED").count())
                .isEqualTo(commitDisabledBefore + 1);
        assertThat(metrics.counter("devflow.board.git.transitions",
                "eventType", EventTypes.GIT_PR_OPENED, "outcome", "SKIPPED", "reason", "DISABLED").count())
                .isEqualTo(prOpenDisabledBefore + 1);
        assertThat(metrics.counter("devflow.board.git.transitions",
                "eventType", EventTypes.GIT_PR_MERGED, "outcome", "SKIPPED", "reason", "DISABLED").count())
                .isEqualTo(prMergeDisabledBefore + 1);
    }
}
