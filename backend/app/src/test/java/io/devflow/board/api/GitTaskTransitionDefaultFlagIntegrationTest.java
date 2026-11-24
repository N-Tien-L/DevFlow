package io.devflow.board.api;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.devflow.common.event.EventTypes;
import io.devflow.gitci.internal.GitDomainEventOutbox;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Verifies the shipped default (BOARD_GIT_AUTO_TRANSITION_ENABLED=false) never poisons outbox or notifications. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "devflow.gitci.outbox.worker-enabled=false"})
class GitTaskTransitionDefaultFlagIntegrationTest {
    static final UUID REPO = UUID.fromString("c199e15a-7a6a-4e56-bc4b-625f47642425");
    static final String KEY = "test-only-t024-default-flag-secret-0123456789";
    static final Instant TIME = Instant.parse("2026-11-18T01:00:00Z");
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17-alpine");
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired GitDomainEventOutbox outbox;
    @Autowired ObjectMapper json;
    @Autowired MeterRegistry metrics;
    UUID workspace, board, todoColumn, task, owner, assignee;

    @DynamicPropertySource static void config(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::getJdbcUrl);
        r.add("spring.datasource.username", DB::getUsername);
        r.add("spring.datasource.password", DB::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        r.add("spring.flyway.enabled", () -> true);
        r.add("devflow.gitci.github-webhook.enabled", () -> true);
        String prefix = "devflow.gitci.github-webhook.bindings[" + REPO + "].";
        r.add(prefix + "reference", () -> "test:t024-default");
        r.add(prefix + "current", () -> KEY);
    }

    @BeforeEach void fixtures() {
        jdbc.update("DELETE FROM repositories");
        jdbc.update("DELETE FROM notifications");
        jdbc.update("DELETE FROM boards");
        jdbc.update("DELETE FROM board_audit_events");
        workspace = UUID.randomUUID();
        owner = user();
        assignee = user();
        jdbc.update("INSERT INTO workspaces(id,name,slug,owner_id) VALUES (?,'fixture',?,?)", workspace, workspace.toString(), owner);
        for (UUID id : List.of(owner, assignee)) {
            jdbc.update("INSERT INTO workspace_members(workspace_id,user_id,role) VALUES (?,?,'OWNER')", workspace, id);
        }
        board = jdbc.queryForObject("INSERT INTO boards(workspace_id,name) VALUES (?,'fixture') RETURNING id", UUID.class, workspace);
        todoColumn = column("TODO", 0);
        column("IN_PROGRESS", 1);
        column("IN_REVIEW", 2);
        column("DONE", 3);
        task = jdbc.queryForObject(
                "INSERT INTO tasks(column_id,title,position,assignee_id) VALUES (?,'Default flag task',0,?) RETURNING id",
                UUID.class, todoColumn, assignee);
        jdbc.update("INSERT INTO repositories(id,workspace_id,provider,remote_repo_id,name,repository_url,webhook_secret_ref) "
                + "VALUES (?,?,'GITHUB','123','fixture','https://github.com/test/repo','test:t024-default')", REPO, workspace);
    }

    @Test void defaultDisabledFlagPublishesOutboxWithoutReceiptsAndDeliversNotifications() throws Exception {
        double beforeCommitDisabled = metrics.counter("devflow.board.git.transitions",
                "eventType", EventTypes.GIT_COMMIT_LINKED, "outcome", "SKIPPED", "reason", "DISABLED").count();
        double beforePrDisabled = metrics.counter("devflow.board.git.transitions",
                "eventType", EventTypes.GIT_PR_OPENED, "outcome", "SKIPPED", "reason", "DISABLED").count();

        assertThat(webhook(0)).isEqualTo(204);
        assertThat(webhook(1)).isEqualTo(204);
        outbox.dispatch();

        assertThat(jdbc.queryForList("SELECT status FROM git_domain_event_outbox", String.class))
                .containsExactly("PUBLISHED", "PUBLISHED");
        assertThat(jdbc.queryForList("SELECT attempts FROM git_domain_event_outbox", Integer.class))
                .containsExactly(1, 1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM board_git_event_receipts", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM board_audit_events", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT column_id FROM tasks WHERE id=?", UUID.class, task)).isEqualTo(todoColumn);
        assertThat(metrics.counter("devflow.board.git.transitions",
                "eventType", EventTypes.GIT_COMMIT_LINKED, "outcome", "SKIPPED", "reason", "DISABLED").count())
                .isEqualTo(beforeCommitDisabled + 1);
        assertThat(metrics.counter("devflow.board.git.transitions",
                "eventType", EventTypes.GIT_PR_OPENED, "outcome", "SKIPPED", "reason", "DISABLED").count())
                .isEqualTo(beforePrDisabled + 1);
        assertThat(jdbc.queryForList("SELECT type FROM notifications WHERE recipient_id=? AND reference_id=?",
                String.class, assignee, task)).containsExactly("git.pr_opened");
    }

    UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,email,password_hash,full_name) VALUES (?,?,'test-hash','fixture')", id, id + "@devflow.test");
        return id;
    }

    UUID column(String category, int position) {
        return jdbc.queryForObject("INSERT INTO columns(board_id,name,position,status_category) VALUES (?,?,?,?) RETURNING id",
                UUID.class, board, category, position, category);
    }

    int webhook(int kind) throws Exception {
        Map<String, Object> payload;
        if (kind == 0) {
            payload = Map.of("repository", Map.of("id", 123), "ref", "refs/heads/main", "after", "a".repeat(40), "deleted", false,
                    "commits", List.of(Map.of("id", "a".repeat(40), "message", "T-001", "timestamp", TIME.toString())));
        } else {
            payload = Map.of("repository", Map.of("id", 123), "action", "opened", "number", 1,
                    "pull_request", Map.of("number", 1, "title", "T-001", "head", Map.of("ref", "feature/T-001", "repo", Map.of("id", 987)),
                            "base", Map.of("ref", "main", "repo", Map.of("id", 123)), "updated_at", TIME.plusSeconds(1).toString(),
                            "state", "open", "merged", false));
        }
        byte[] body = json.writeValueAsBytes(payload);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/gitci/webhook/github?repositoryId=" + REPO))
                .timeout(java.time.Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .header("X-Hub-Signature-256", "sha256=" + HexFormat.of().formatHex(mac.doFinal(body)))
                .header("X-GitHub-Delivery", UUID.randomUUID().toString())
                .header("X-GitHub-Event", kind == 0 ? "push" : "pull_request")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
    }
}
