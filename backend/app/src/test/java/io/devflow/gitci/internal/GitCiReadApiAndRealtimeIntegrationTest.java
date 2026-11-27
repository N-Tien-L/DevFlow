package io.devflow.gitci.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.devflow.auth.internal.security.JwtTokenProvider;
import io.devflow.board.api.BoardMutationMessage;
import io.devflow.board.api.BoardMutationType;
import io.devflow.board.internal.BoardRealtimeBroadcaster;
import io.devflow.common.event.TaskGitActivityChangedEvent;
import io.devflow.common.event.TaskReferencesRemovedEvent;
import io.devflow.common.event.WorkspaceDeletedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end PostgreSQL 17 integration tests for T-026 GitCI Read APIs, security/IDOR,
 * aggregation rules, realtime STOMP invalidations, query plans, and lifecycle cleanup.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "devflow.gitci.outbox.worker-enabled=false",
        "devflow.gitci.ci.fetch-enabled=false",
        "devflow.gitci.ci.publish-enabled=false",
        "devflow.board.git.auto-transition-enabled=false",
        "devflow.gitci.read.rate-limit.capacity=100"
})
class GitCiReadApiAndRealtimeIntegrationTest {

    private static final UUID REPO_FE = UUID.fromString("c199e15a-7a6a-4e56-bc4b-625f47640026");
    private static final UUID REPO_BE = UUID.fromString("c199e15a-7a6a-4e56-bc4b-625f47640027");
    private static final UUID REPO_OTHER = UUID.fromString("c199e15a-7a6a-4e56-bc4b-625f47640028");
    private static final String SECRET = "test-only-t026-secret-0123456789-abcdef";
    private static final Instant T0 = Instant.parse("2026-11-27T09:00:00Z");

    @Container
    private static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17-alpine");

    @LocalServerPort int port;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired JwtTokenProvider tokens;
    @Autowired GitDomainEventOutbox gitOutbox;
    @Autowired ApplicationEventPublisher events;
    @Autowired PlatformTransactionManager txManager;
    @Autowired MeterRegistry metrics;
    @MockitoSpyBean BoardRealtimeBroadcaster broadcaster;

    private final HttpClient http = HttpClient.newHttpClient();

    private UUID wsA, wsB, boardA, boardArchived, boardB, colA, colArchived, colB;
    private UUID userA1, userA2, userB1;
    private UUID task1, task2, task3, taskArchived, taskB;

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::getJdbcUrl);
        r.add("spring.datasource.username", DB::getUsername);
        r.add("spring.datasource.password", DB::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        r.add("spring.flyway.enabled", () -> true);
        r.add("devflow.gitci.github-webhook.enabled", () -> true);
        for (UUID repo : List.of(REPO_FE, REPO_BE, REPO_OTHER)) {
            String prefix = "devflow.gitci.github-webhook.bindings[" + repo + "].";
            r.add(prefix + "reference", () -> "test:t026:" + repo);
            r.add(prefix + "current", () -> secretFor(repo));
        }
    }

    private static String secretFor(UUID repo) {
        return SECRET + "-" + repo;
    }

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM repositories");
        jdbc.update("DELETE FROM boards");
        jdbc.update("DELETE FROM board_task_key_counters");
        jdbc.update("DELETE FROM workspace_members");
        jdbc.update("DELETE FROM workspaces");
        jdbc.update("DELETE FROM users");
        clearInvocations(broadcaster);

        userA1 = createUser();
        userA2 = createUser();
        userB1 = createUser();

        wsA = createWorkspace(userA1);
        addMember(wsA, userA1);
        addMember(wsA, userA2);
        wsB = createWorkspace(userB1);
        addMember(wsB, userB1);

        boardA = createBoard(wsA, false);
        colA = createColumn(boardA, "IN_PROGRESS", 0);
        task1 = createTask(colA, "Task One", 0);      // T-001
        task2 = createTask(colA, "Task Two", 1);      // T-002
        task3 = createTask(colA, "Task Three", 2);    // T-003 (empty)

        boardArchived = createBoard(wsA, true);
        colArchived = createColumn(boardArchived, "DONE", 0);
        taskArchived = createTask(colArchived, "Archived Task", 0); // T-004

        boardB = createBoard(wsB, false);
        colB = createColumn(boardB, "TODO", 0);
        taskB = createTask(colB, "Foreign Task", 0);  // T-001 in wsB

        insertRepo(REPO_FE, wsA, "1001", "devflow/web", "https://github.com/devflow/web");
        insertRepo(REPO_BE, wsA, "1002", "devflow/api", "https://github.com/devflow/api.git");
        insertRepo(REPO_OTHER, wsB, "2001", "other/repo", "https://github.com/other/repo");
    }

    @Test
    void aggregatesCommitsPrsCiRerunsHeadShaAndActivityEndpointsAccurately() throws Exception {
        String sha1 = "a".repeat(40);
        String sha2 = "b".repeat(40);
        String shaPrOnly = "c".repeat(40);

        // 1. Push commit linked to T-001 & T-002 (multi-task link via V16)
        assertThat(sendPush(REPO_FE, 1001, "feature/work", List.of(
                Map.of("id", sha1, "message", "feat: T-001 T-002 <script>alert(1)</script>\u0007", "timestamp", T0.toString())
        ))).isEqualTo(204);

        // 2. Push second commit to T-001 in BE repo (multi-repo)
        assertThat(sendPush(REPO_BE, 1002, "main", List.of(
                Map.of("id", sha2, "message", "fix(api): T-001 fix query", "timestamp", T0.plusSeconds(60).toString())
        ))).isEqualTo(204);

        // 3. PRs on T-001: CLOSED newer (#10), MERGED (#11), OPEN older (#12) -> OPEN must win as primaryPr
        assertThat(sendPr(REPO_FE, 1001, 10, "closed", false, "T-001 closed PR", "feat/old", sha1, T0.plusSeconds(300))).isEqualTo(204);
        assertThat(sendPr(REPO_FE, 1001, 11, "closed", true, "T-001 merged PR", "feat/m", sha1, T0.plusSeconds(200))).isEqualTo(204);
        assertThat(sendPr(REPO_FE, 1001, 12, "opened", false, "T-001 open PR", "feat/open", sha1, T0.plusSeconds(100))).isEqualTo(204);

        // 4. PR-only link on T-002 with head_sha (no commit with shaPrOnly) -> CI failure on shaPrOnly must still attach to T-002!
        assertThat(sendPr(REPO_BE, 1002, 20, "opened", false, "T-002 PR head CI", "feat/pr-ci", shaPrOnly, T0.plusSeconds(150))).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT head_sha FROM pull_requests WHERE repository_id=? AND pr_number=20",
                String.class, REPO_BE)).isEqualTo(shaPrOnly);

        // 5. Workflow runs:
        //    - REPO_FE sha1: run 501 attempt 1 FAILED -> attempt 2 SUCCESS (rerun clears failure!)
        //    - REPO_BE sha2: run 601 attempt 1 FAILED -> T-001 has FE=SUCCESS, BE=FAILED -> overall FAILED pointing to BE!
        //    - REPO_BE shaPrOnly: run 701 attempt 1 FAILED -> attaches to T-002 via pull_requests.head_sha!
        assertThat(sendWorkflow(REPO_FE, 1001, 501, 1, sha1, "failure", T0.plusSeconds(400))).isEqualTo(204);
        assertThat(sendWorkflow(REPO_FE, 1001, 501, 2, sha1, "success", T0.plusSeconds(500))).isEqualTo(204);
        assertThat(sendWorkflow(REPO_BE, 1002, 601, 1, sha2, "failure", T0.plusSeconds(520))).isEqualTo(204);
        assertThat(sendWorkflow(REPO_BE, 1002, 701, 1, shaPrOnly, "failure", T0.plusSeconds(540))).isEqualTo(204);

        // Inject author_email and raw_log_snippet to prove privacy exclusion (SEC-08)
        jdbc.update("UPDATE git_commits SET author_email='private@secret.local' WHERE sha=?", sha1);
        jdbc.update("UPDATE ci_failures SET raw_log_snippet='TOP_SECRET_LOG_LINE', error_summary='RAW_ERR'");

        // Batch read for T-001, T-002, T-003 (empty)
        MvcResult batchResult = mvc.perform(get("/api/v1/gitci/workspaces/{ws}/task-summaries", wsA)
                        .param("taskIds", task1 + "," + task2 + "," + task3)
                        .header("Authorization", bearer(userA1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspaceId").value(wsA.toString()))
                .andExpect(jsonPath("$.items.length()").value(2)) // task3 omitted cleanly
                .andReturn();

        String rawJson = batchResult.getResponse().getContentAsString();
        assertThat(rawJson).doesNotContain("private@secret.local", "TOP_SECRET_LOG_LINE", "RAW_ERR", "test:t026");

        JsonNode items = json.readTree(rawJson).path("items");
        JsonNode t1 = items.get(0);
        assertThat(t1.path("taskId").asText()).isEqualTo(task1.toString());
        assertThat(t1.path("commitCount").asInt()).isEqualTo(2);
        assertThat(t1.path("latestCommit").path("sha").asText()).isEqualTo(sha2);
        assertThat(t1.path("latestCommit").path("url").asText()).isEqualTo("https://github.com/devflow/api/commit/" + sha2);
        assertThat(t1.path("prCount").asInt()).isEqualTo(3);
        assertThat(t1.path("openPrCount").asInt()).isEqualTo(1);
        assertThat(t1.path("primaryPr").path("prNumber").asInt()).isEqualTo(12);
        assertThat(t1.path("primaryPr").path("status").asText()).isEqualTo("OPEN");
        assertThat(t1.path("primaryPr").path("url").asText()).isEqualTo("https://github.com/devflow/web/pull/12");
        assertThat(t1.path("ci").path("status").asText()).isEqualTo("FAILED");
        assertThat(t1.path("ci").path("failedRunCount").asInt()).isEqualTo(1);
        assertThat(t1.path("ci").path("url").asText()).isEqualTo("https://github.com/devflow/api/actions/runs/601/attempts/1");

        JsonNode t2 = items.get(1);
        assertThat(t2.path("taskId").asText()).isEqualTo(task2.toString());
        assertThat(t2.path("commitCount").asInt()).isEqualTo(1);
        assertThat(t2.path("latestCommit").path("messagePreview").asText()).doesNotContain("\u0007");
        assertThat(t2.path("prCount").asInt()).isEqualTo(1);
        assertThat(t2.path("ci").path("status").asText()).isEqualTo("FAILED");
        assertThat(t2.path("ci").path("commitSha").asText()).isEqualTo(shaPrOnly);
        assertThat(t2.path("ci").path("url").asText()).isEqualTo("https://github.com/devflow/api/actions/runs/701/attempts/1");

        // Rerun BE run 601 attempt 2 SUCCESS -> T-001 flips from FAILED to SUCCESS!
        assertThat(sendWorkflow(REPO_BE, 1002, 601, 2, sha2, "success", T0.plusSeconds(600))).isEqualTo(204);
        mvc.perform(get("/api/v1/gitci/workspaces/{ws}/task-summaries", wsA)
                        .param("taskIds", task1.toString())
                        .header("Authorization", bearer(userA1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].ci.status").value("SUCCESS"))
                .andExpect(jsonPath("$.items[0].ci.failedRunCount").value(0))
                .andExpect(jsonPath("$.items[0].ci.runAttempt").value(2));

        // Single-task activity endpoint with explicit limits
        mvc.perform(get("/api/v1/gitci/workspaces/{ws}/tasks/{taskId}/activity", wsA, task1)
                        .param("commitsLimit", "1")
                        .param("prsLimit", "2")
                        .param("ciRunsLimit", "1")
                        .header("Authorization", bearer(userA1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCommits").value(2))
                .andExpect(jsonPath("$.commits.length()").value(1))
                .andExpect(jsonPath("$.totalPullRequests").value(3))
                .andExpect(jsonPath("$.pullRequests.length()").value(2))
                .andExpect(jsonPath("$.pullRequests[0].prNumber").value(12))
                .andExpect(jsonPath("$.pullRequests[1].prNumber").value(11))
                .andExpect(jsonPath("$.totalCiRuns").value(2))
                .andExpect(jsonPath("$.ciRuns.length()").value(1));

        // Empty task activity returns summary=null and empty arrays
        mvc.perform(get("/api/v1/gitci/workspaces/{ws}/tasks/{taskId}/activity", wsA, task3)
                        .header("Authorization", bearer(userA1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").isEmpty())
                .andExpect(jsonPath("$.totalCommits").value(0))
                .andExpect(jsonPath("$.commits").isEmpty());
    }

    @Test
    void enforcesAuthMembershipAntiIdorValidationPrivacyAndArchivedReadability() throws Exception {
        String shaB = "d".repeat(40);
        assertThat(sendPush(REPO_OTHER, 2001, "main", List.of(
                Map.of("id", shaB, "message", "secret B: T-001", "timestamp", T0.toString())
        ))).isEqualTo(204);

        // 401 unauthenticated
        mvc.perform(get("/api/v1/gitci/workspaces/{ws}/task-summaries", wsA).param("taskIds", task1.toString()))
                .andExpect(status().isUnauthorized());

        // 401 revoked JWT after password reset increments users.token_version (SEC-01)
        String oldToken = bearer(userA2);
        jdbc.update("UPDATE users SET token_version = token_version + 1 WHERE id = ?", userA2);
        mvc.perform(get("/api/v1/gitci/workspaces/{ws}/task-summaries", wsA)
                        .param("taskIds", task1.toString())
                        .header("Authorization", oldToken))
                .andExpect(status().isUnauthorized());
        jdbc.update("UPDATE users SET token_version = 0 WHERE id = ?", userA2);

        // 404 non-member (anti-enumeration)
        mvc.perform(get("/api/v1/gitci/workspaces/{ws}/task-summaries", wsA)
                        .param("taskIds", task1.toString())
                        .header("Authorization", bearer(userB1)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("WORKSPACE_NOT_FOUND"));

        // Anti-IDOR: userA1 asking for wsB's taskB under wsA sees zero wsB rows
        mvc.perform(get("/api/v1/gitci/workspaces/{ws}/task-summaries", wsA)
                        .param("taskIds", taskB.toString())
                        .header("Authorization", bearer(userA1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get("/api/v1/gitci/workspaces/{ws}/tasks/{taskId}/activity", wsA, taskB)
                        .header("Authorization", bearer(userA1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").isEmpty())
                .andExpect(jsonPath("$.totalCommits").value(0));

        // 400 validation errors: missing taskIds, malformed UUID, >50 distinct UUIDs, invalid limits
        mvc.perform(get("/api/v1/gitci/workspaces/{ws}/task-summaries", wsA).header("Authorization", bearer(userA1)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(get("/api/v1/gitci/workspaces/{ws}/task-summaries", wsA)
                        .param("taskIds", "not-a-uuid")
                        .header("Authorization", bearer(userA1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        String fiftyOne = java.util.stream.IntStream.range(0, 51)
                .mapToObj(i -> UUID.randomUUID().toString()).collect(Collectors.joining(","));
        mvc.perform(get("/api/v1/gitci/workspaces/{ws}/task-summaries", wsA)
                        .param("taskIds", fiftyOne)
                        .header("Authorization", bearer(userA1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(get("/api/v1/gitci/workspaces/{ws}/tasks/{taskId}/activity", wsA, task1)
                        .param("commitsLimit", "51")
                        .header("Authorization", bearer(userA1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(get("/api/v1/gitci/workspaces/{ws}/tasks/{taskId}/activity", wsA, task1)
                        .param("ciRunsLimit", "0")
                        .header("Authorization", bearer(userA1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // Archived board task is readable by workspace member (even if manually linked historically)
        UUID commitId = UUID.randomUUID();
        jdbc.update("INSERT INTO git_commits(id,repository_id,sha,message,committed_at) VALUES (?,?,?,?,?)",
                commitId, REPO_FE, "e".repeat(40), "archived link", Timestamp.from(T0));
        jdbc.update("INSERT INTO git_commit_task_links(commit_id,task_id,source_kind) VALUES (?,?,'LEGACY')",
                commitId, taskArchived);
        mvc.perform(get("/api/v1/gitci/workspaces/{ws}/task-summaries", wsA)
                        .param("taskIds", taskArchived.toString())
                        .header("Authorization", bearer(userA1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].commitCount").value(1));
    }

    @Test
    void broadcastsGitActivityStompForDisabledNoopPrClosedAndCiUpdatesWithTenantIsolation() throws Exception {
        String sha = "f".repeat(40);
        // Push commit while auto-transition-enabled=false -> dispatch outbox -> DISABLED still broadcasts gitActivity!
        assertThat(sendPush(REPO_FE, 1001, "main", List.of(
                Map.of("id", sha, "message", "T-001 first commit", "timestamp", T0.toString())
        ))).isEqualTo(204);
        gitOutbox.dispatch();

        ArgumentCaptor<BoardMutationMessage> captor = ArgumentCaptor.forClass(BoardMutationMessage.class);
        Mockito.verify(broadcaster, Mockito.atLeastOnce()).broadcast(captor.capture());
        assertThat(captor.getAllValues()).anySatisfy(msg -> {
            assertThat(msg.boardId()).isEqualTo(boardA);
            assertThat(msg.type()).isEqualTo(BoardMutationType.CARD_UPDATED);
            assertThat(msg.actorId()).isNull();
            assertThat(msg.data().taskId()).isEqualTo(task1);
            assertThat(msg.data().changedFields()).containsExactly("gitActivity");
        });

        // PR closed without merge -> immediate afterCommit CARD_UPDATED(gitActivity) without outbox transition
        clearInvocations(broadcaster);
        assertThat(sendPr(REPO_FE, 1001, 30, "closed", false, "T-001 closed without merge", "feat/c", sha, T0.plusSeconds(30)))
                .isEqualTo(204);
        Mockito.verify(broadcaster).broadcast(Mockito.argThat(msg ->
                msg.boardId().equals(boardA)
                        && msg.type() == BoardMutationType.CARD_UPDATED
                        && msg.data().changedFields().equals(List.of("gitActivity"))
                        && task1.equals(msg.data().taskId())));

        // CI workflow failure -> immediate afterCommit CARD_UPDATED(gitActivity)
        clearInvocations(broadcaster);
        assertThat(sendWorkflow(REPO_FE, 1001, 801, 1, sha, "failure", T0.plusSeconds(60))).isEqualTo(204);
        Mockito.verify(broadcaster).broadcast(Mockito.argThat(msg ->
                msg.boardId().equals(boardA)
                        && msg.type() == BoardMutationType.CARD_UPDATED
                        && msg.data().changedFields().equals(List.of("gitActivity"))
                        && task1.equals(msg.data().taskId())));

        // Cross-tenant event mixing wsA and wsB tasks under wsA ONLY broadcasts to boardA (never boardB)
        clearInvocations(broadcaster);
        events.publishEvent(new TaskGitActivityChangedEvent(
                UUID.randomUUID(), wsA, REPO_FE, Set.of(task1, taskB),
                TaskGitActivityChangedEvent.ActivityKind.CI_UPDATED, Instant.now()));
        ArgumentCaptor<BoardMutationMessage> isolated = ArgumentCaptor.forClass(BoardMutationMessage.class);
        Mockito.verify(broadcaster, Mockito.times(1)).broadcast(isolated.capture());
        assertThat(isolated.getValue().boardId()).isEqualTo(boardA);
        assertThat(isolated.getValue().data().taskId()).isEqualTo(task1);
    }

    @Test
    void cleansUpAssociationsOnTaskAndWorkspaceDeletionAndRecordsLowCardinalityMetrics() throws Exception {
        String sha = "1".repeat(40);
        assertThat(sendPush(REPO_FE, 1001, "main", List.of(
                Map.of("id", sha, "message", "T-001 T-002 shared", "timestamp", T0.toString())
        ))).isEqualTo(204);

        // Task deletion cleans up links for task1 only; task2 remains intact
        new TransactionTemplate(txManager).executeWithoutResult(tx ->
                events.publishEvent(new TaskReferencesRemovedEvent(Set.of(task1))));

        mvc.perform(get("/api/v1/gitci/workspaces/{ws}/task-summaries", wsA)
                        .param("taskIds", task1 + "," + task2)
                        .header("Authorization", bearer(userA1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].taskId").value(task2.toString()));

        // Workspace deletion cascades all GitCI rows
        new TransactionTemplate(txManager).executeWithoutResult(tx ->
                events.publishEvent(new WorkspaceDeletedEvent(wsA, userA1, Instant.now())));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM repositories WHERE workspace_id=?", Integer.class, wsA))
                .isZero();

        // Metrics recorded with low-cardinality tags only
        assertThat(metrics.find("devflow.gitci.read.requests").counters()).isNotEmpty();
        metrics.find("devflow.gitci.read.requests").counters().forEach(counter ->
                assertThat(counter.getId().getTags().stream().map(io.micrometer.core.instrument.Tag::getKey))
                        .doesNotContain("workspaceId", "taskId", "sha", "userId"));
    }

    @Test
    void preservesAllSupportedCiStatusesVerbatimFromDatabase() throws Exception {
        List<String> statuses = List.of(
                "TIMED_OUT", "ACTION_REQUIRED", "RUNNING", "PENDING",
                "CANCELLED", "SKIPPED", "NEUTRAL", "STALE", "UNKNOWN");
        List<UUID> statusTasks = new ArrayList<>();
        for (int i = 0; i < statuses.size(); i++) {
            UUID t = createTask(colA, "CI Status " + statuses.get(i), 100 + i);
            statusTasks.add(t);
            UUID commitId = UUID.randomUUID();
            String sha = String.format("a%039x", i + 1);
            jdbc.update("INSERT INTO git_commits(id,repository_id,sha,message,committed_at) VALUES (?,?,?,?,?)",
                    commitId, REPO_FE, sha, "status " + statuses.get(i), Timestamp.from(T0.plusSeconds(i)));
            jdbc.update("INSERT INTO git_commit_task_links(commit_id,task_id,source_kind) VALUES (?,?,'MESSAGE')",
                    commitId, t);
            jdbc.update("""
                    INSERT INTO ci_pipelines(repository_id,pipeline_id,run_attempt,commit_sha,branch,status,finished_at)
                    VALUES (?,?,1,?,'main',?,?)
                    """, REPO_FE, Integer.toString(5000 + i), sha, statuses.get(i),
                    Timestamp.from(T0.plusSeconds(i + 5)));
        }

        String csvIds = statusTasks.stream().map(UUID::toString).collect(Collectors.joining(","));
        MvcResult result = mvc.perform(get("/api/v1/gitci/workspaces/{ws}/task-summaries", wsA)
                        .param("taskIds", csvIds)
                        .header("Authorization", bearer(userA1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(statuses.size()))
                .andReturn();

        JsonNode items = json.readTree(result.getResponse().getContentAsString()).path("items");
        for (int i = 0; i < statuses.size(); i++) {
            assertThat(items.get(i).path("ci").path("status").asText()).isEqualTo(statuses.get(i));
        }
    }

    @Test
    void queryPlanUsesIndexesAndBatchLatencyStaysUnder200MsOnRealisticDataset() throws Exception {
        // Seed 5,000 background noise rows across unrelated tasks so planner sees realistic selectivity
        jdbc.update("""
                WITH noise_commits AS (
                    INSERT INTO git_commits(id, repository_id, sha, message, committed_at)
                    SELECT gen_random_uuid(), ?, lpad(to_hex(100000 + g), 40, '0'), 'noise ' || g,
                           CAST(? AS timestamptz) + make_interval(secs => g)
                    FROM generate_series(1, 5000) AS g
                    RETURNING id, sha
                )
                INSERT INTO git_commit_task_links(commit_id, task_id, source_kind)
                SELECT id, gen_random_uuid(), 'MESSAGE' FROM noise_commits
                """, REPO_OTHER, Timestamp.from(T0));
        jdbc.update("""
                INSERT INTO ci_pipelines(repository_id, pipeline_id, run_attempt, commit_sha, branch, status, finished_at)
                SELECT ?, (200000 + g)::text, 1, lpad(to_hex(100000 + g), 40, '0'), 'main', 'SUCCESS',
                       CAST(? AS timestamptz) + make_interval(secs => g)
                FROM generate_series(1, 2500) AS g
                """, REPO_OTHER, Timestamp.from(T0));

        // Seed 50 queried tasks in wsA with 500 commits, 100 PRs, and 250 CI pipelines
        List<UUID> batchTasks = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            batchTasks.add(createTask(colA, "Perf Task " + i, i + 10));
        }
        for (int c = 0; c < 500; c++) {
            UUID cid = UUID.randomUUID();
            String sha = String.format("%040x", c + 1);
            UUID repo = (c % 2 == 0) ? REPO_FE : REPO_BE;
            jdbc.update("INSERT INTO git_commits(id,repository_id,sha,message,committed_at) VALUES (?,?,?,?,?)",
                    cid, repo, sha, "perf commit " + c, Timestamp.from(T0.plusSeconds(c)));
            jdbc.update("INSERT INTO git_commit_task_links(commit_id,task_id,source_kind) VALUES (?,?,'MESSAGE')",
                    cid, batchTasks.get(c % 50));
            if (c < 250) {
                jdbc.update("""
                        INSERT INTO ci_pipelines(repository_id,pipeline_id,run_attempt,commit_sha,branch,status,finished_at)
                        VALUES (?,?,1,?,'main',?,?)
                        """, repo, Integer.toString(10000 + c), sha, (c % 5 == 0) ? "FAILED" : "SUCCESS",
                        Timestamp.from(T0.plusSeconds(c + 5)));
            }
        }
        for (int p = 0; p < 100; p++) {
            UUID pid = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO pull_requests(id,repository_id,pr_number,title,source_branch,target_branch,head_sha,status,pr_url,provider_updated_at)
                    VALUES (?,?,?,?,?,'main',?,?,?,?)
                    """, pid, REPO_FE, p + 100, "PR " + p, "feat/" + p, String.format("%040x", p + 1),
                    (p % 3 == 0) ? "OPEN" : "MERGED", "https://github.com/devflow/web/pull/" + (p + 100),
                    Timestamp.from(T0.plusSeconds(p + 100)));
            jdbc.update("INSERT INTO git_pull_request_task_links(pull_request_id,task_id,source_kind) VALUES (?,?,'TITLE')",
                    pid, batchTasks.get(p % 50));
        }
        jdbc.execute("ANALYZE git_commit_task_links, git_commits, git_pull_request_task_links, pull_requests, ci_pipelines, repositories");

        String csvIds = batchTasks.stream().map(UUID::toString).collect(Collectors.joining(","));
        String authHeader = bearer(userA2);
        // Warmup 5 calls
        for (int w = 0; w < 5; w++) {
            mvc.perform(get("/api/v1/gitci/workspaces/{ws}/task-summaries", wsA)
                            .param("taskIds", csvIds).header("Authorization", authHeader))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(50));
        }

        // Verify EXPLAIN (ANALYZE, BUFFERS) uses indexes on git_commit_task_links and ci_pipelines
        String inList = batchTasks.stream().map(id -> "'" + id + "'::uuid").collect(Collectors.joining(","));
        String commitPlanText = String.join("\n", jdbc.queryForList("""
                EXPLAIN (ANALYZE, BUFFERS)
                WITH ranked AS (
                    SELECT l.task_id, c.sha, c.message, c.committed_at, r.name AS repo_name, r.repository_url,
                           COUNT(*) OVER (PARTITION BY l.task_id) AS commit_count,
                           ROW_NUMBER() OVER (PARTITION BY l.task_id ORDER BY c.committed_at DESC, c.created_at DESC, c.id DESC) AS rn
                    FROM git_commit_task_links l
                    JOIN git_commits c ON c.id = l.commit_id
                    JOIN repositories r ON r.id = c.repository_id
                    WHERE r.workspace_id = '%s'::uuid AND l.task_id IN (%s)
                )
                SELECT task_id, commit_count, sha, message, committed_at, repo_name, repository_url FROM ranked WHERE rn = 1
                """.formatted(wsA, inList), String.class));
        String ciPlanText = String.join("\n", jdbc.queryForList("""
                EXPLAIN (ANALYZE, BUFFERS)
                WITH linked_shas AS (
                    SELECT task_id, repository_id, sha,
                           MAX(sha_time) AS sha_time, MAX(sha_created_at) AS sha_created_at,
                           MAX(repo_name) AS repo_name, MAX(repository_url) AS repository_url
                    FROM (
                        SELECT l.task_id, c.repository_id, c.sha,
                               c.committed_at AS sha_time, c.created_at AS sha_created_at,
                               r.name AS repo_name, r.repository_url
                        FROM git_commit_task_links l
                        JOIN git_commits c ON c.id = l.commit_id
                        JOIN repositories r ON r.id = c.repository_id
                        WHERE r.workspace_id = '%s'::uuid AND l.task_id IN (%s)
                        UNION ALL
                        SELECT pl.task_id, pr.repository_id, pr.head_sha AS sha,
                               COALESCE(pr.provider_updated_at, pr.updated_at) AS sha_time,
                               pr.created_at AS sha_created_at,
                               r.name AS repo_name, r.repository_url
                        FROM git_pull_request_task_links pl
                        JOIN pull_requests pr ON pr.id = pl.pull_request_id
                        JOIN repositories r ON r.id = pr.repository_id
                        WHERE r.workspace_id = '%s'::uuid AND pl.task_id IN (%s)
                          AND pr.head_sha IS NOT NULL
                    ) raw_shas
                    GROUP BY task_id, repository_id, sha
                ),
                ranked_shas AS (
                    SELECT task_id, repository_id, sha, sha_time, sha_created_at, repo_name, repository_url,
                           (ROW_NUMBER() OVER (
                               PARTITION BY task_id, repository_id
                               ORDER BY sha_time DESC, sha_created_at DESC, sha DESC
                           ) > 1) AS stale_for_latest_commit
                    FROM linked_shas
                ),
                matched_pipelines AS (
                    SELECT rs.task_id, rs.repository_id, rs.sha, rs.sha_time, rs.sha_created_at,
                           rs.stale_for_latest_commit, rs.repo_name, rs.repository_url,
                           p.pipeline_id, p.run_attempt, p.status, p.provider_conclusion,
                           p.started_at, p.finished_at, p.provider_updated_at, p.created_at AS pipeline_created_at,
                           ROW_NUMBER() OVER (
                               PARTITION BY rs.task_id, p.repository_id, p.pipeline_id
                               ORDER BY p.run_attempt DESC
                           ) AS attempt_rn
                    FROM ranked_shas rs
                    JOIN ci_pipelines p
                      ON p.repository_id = rs.repository_id AND p.commit_sha = rs.sha
                )
                SELECT task_id, repository_id, sha, sha_time, sha_created_at,
                       stale_for_latest_commit, repo_name, repository_url,
                       pipeline_id, run_attempt, status, provider_conclusion,
                       started_at, finished_at, provider_updated_at, pipeline_created_at
                FROM matched_pipelines
                WHERE attempt_rn = 1
                """.formatted(wsA, inList, wsA, inList), String.class));

        String singleTaskCiPlanText = String.join("\n", jdbc.queryForList("""
                EXPLAIN (ANALYZE, BUFFERS)
                SELECT p.pipeline_id, p.run_attempt, p.status
                FROM ci_pipelines p
                WHERE p.repository_id = '%s'::uuid AND p.commit_sha = '%s'
                """.formatted(REPO_FE, String.format("%040x", 1)), String.class));

        assertThat(commitPlanText).contains("idx_git_commit_links_task");
        assertThat(ciPlanText).contains("idx_git_commit_links_task");
        assertThat(ciPlanText).doesNotContain("Rows Removed by Join Filter");
        assertThat(singleTaskCiPlanText).contains("idx_ci_pipelines_repo_commit");

        List<Double> latenciesMs = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            long begin = System.nanoTime();
            mvc.perform(get("/api/v1/gitci/workspaces/{ws}/task-summaries", wsA)
                            .param("taskIds", csvIds).header("Authorization", authHeader))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(50));
            latenciesMs.add((System.nanoTime() - begin) / 1_000_000.0);
        }
        Collections.sort(latenciesMs);
        double p50 = latenciesMs.get(latenciesMs.size() / 2);
        double p95 = latenciesMs.get((int) Math.ceil(latenciesMs.size() * 0.95) - 1);
        System.out.printf("T-026 batch(50) latency ms: min=%.2f p50=%.2f p95=%.2f max=%.2f%n",
                latenciesMs.get(0), p50, p95, latenciesMs.get(latenciesMs.size() - 1));
        assertThat(p95).isLessThan(200.0);
    }

    private UUID createUser() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,email,password_hash,full_name) VALUES (?,?,'hash','User')", id, id + "@devflow.test");
        return id;
    }

    private UUID createWorkspace(UUID owner) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO workspaces(id,name,slug,owner_id) VALUES (?,'ws',?,?)", id, id.toString(), owner);
        return id;
    }

    private void addMember(UUID ws, UUID user) {
        jdbc.update("INSERT INTO workspace_members(workspace_id,user_id,role) VALUES (?,?,'MEMBER')", ws, user);
    }

    private UUID createBoard(UUID ws, boolean archived) {
        return jdbc.queryForObject("INSERT INTO boards(workspace_id,name,is_archived) VALUES (?,'Board',?) RETURNING id",
                UUID.class, ws, archived);
    }

    private UUID createColumn(UUID board, String category, int pos) {
        return jdbc.queryForObject("INSERT INTO columns(board_id,name,position,status_category) VALUES (?,'Col',?,?) RETURNING id",
                UUID.class, board, pos, category);
    }

    private UUID createTask(UUID col, String title, int pos) {
        return jdbc.queryForObject("INSERT INTO tasks(column_id,title,position) VALUES (?,?,?) RETURNING id",
                UUID.class, col, title, pos);
    }

    private void insertRepo(UUID id, UUID ws, String remoteId, String name, String url) {
        jdbc.update("INSERT INTO repositories(id,workspace_id,provider,remote_repo_id,name,repository_url,webhook_secret_ref) "
                + "VALUES (?,?,'GITHUB',?,?,?,?)", id, ws, remoteId, name, url, "test:t026:" + id);
    }

    private String bearer(UUID userId) {
        return "Bearer " + tokens.generateAccessToken(userId, userId + "@devflow.test");
    }

    private int sendPush(UUID repoId, int remoteId, String branch, List<Map<String, Object>> commits) throws Exception {
        String after = commits.get(commits.size() - 1).get("id").toString();
        return sendWebhook(repoId, "push", Map.of(
                "repository", Map.of("id", remoteId),
                "ref", "refs/heads/" + branch,
                "after", after,
                "deleted", false,
                "commits", commits));
    }

    private int sendPr(UUID repoId, int remoteId, int number, String action, boolean merged,
                       String title, String headBranch, String headSha, Instant updatedAt) throws Exception {
        return sendWebhook(repoId, "pull_request", Map.of(
                "repository", Map.of("id", remoteId),
                "action", action,
                "number", number,
                "pull_request", Map.of(
                        "number", number,
                        "title", title,
                        "head", Map.of("ref", headBranch, "sha", headSha, "repo", Map.of("id", remoteId)),
                        "base", Map.of("ref", "main", "repo", Map.of("id", remoteId)),
                        "updated_at", updatedAt.toString(),
                        "state", "closed".equals(action) ? "closed" : "open",
                        "merged", merged)));
    }

    private int sendWorkflow(UUID repoId, int remoteId, long runId, int attempt, String headSha,
                             String conclusion, Instant updatedAt) throws Exception {
        return sendWebhook(repoId, "workflow_run", Map.of(
                "repository", Map.of("id", remoteId),
                "action", "completed",
                "workflow_run", Map.of(
                        "id", runId,
                        "run_attempt", attempt,
                        "head_sha", headSha,
                        "head_branch", "main",
                        "status", "completed",
                        "conclusion", conclusion,
                        "run_started_at", updatedAt.minusSeconds(10).toString(),
                        "updated_at", updatedAt.toString())));
    }

    @Test
    void handlesCiBeforePushNoBranchLeakageStaleCommitAndNewCommitFixOverOldRerun() throws Exception {
        String shaBeforePush = "2".repeat(40);
        String shaNewerNoCi = "3".repeat(40);
        String shaFixGreen = "4".repeat(40);
        String shaReusedBranchOtherTask = "5".repeat(40);

        // API-09: CI failure arrives BEFORE push webhook
        assertThat(sendWorkflow(REPO_FE, 1001, 901, 1, shaBeforePush, "failure", T0.plusSeconds(10))).isEqualTo(204);
        mvc.perform(get("/api/v1/gitci/workspaces/{ws}/task-summaries", wsA)
                        .param("taskIds", task1.toString()).header("Authorization", bearer(userA1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());

        // Push links shaBeforePush to T-001 on branch feature/reuse -> CI failure connects immediately
        assertThat(sendPush(REPO_FE, 1001, "feature/reuse", List.of(
                Map.of("id", shaBeforePush, "message", "T-001 initial", "timestamp", T0.toString())
        ))).isEqualTo(204);
        mvc.perform(get("/api/v1/gitci/workspaces/{ws}/task-summaries", wsA)
                        .param("taskIds", task1.toString()).header("Authorization", bearer(userA1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].ci.status").value("FAILED"))
                .andExpect(jsonPath("$.items[0].ci.staleForLatestCommit").value(false));

        // API-08: Push newer commit without CI yet -> keeps FAILED with staleForLatestCommit=true
        assertThat(sendPush(REPO_FE, 1001, "feature/reuse", List.of(
                Map.of("id", shaNewerNoCi, "message", "T-001 wip fix", "timestamp", T0.plusSeconds(120).toString())
        ))).isEqualTo(204);
        mvc.perform(get("/api/v1/gitci/workspaces/{ws}/task-summaries", wsA)
                        .param("taskIds", task1.toString()).header("Authorization", bearer(userA1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].ci.status").value("FAILED"))
                .andExpect(jsonPath("$.items[0].ci.staleForLatestCommit").value(true));

        // API-07: Push green fix commit -> SUCCESS, and late rerun failure on old commit does NOT resurrect red badge
        assertThat(sendPush(REPO_FE, 1001, "feature/reuse", List.of(
                Map.of("id", shaFixGreen, "message", "T-001 green fix", "timestamp", T0.plusSeconds(240).toString())
        ))).isEqualTo(204);
        assertThat(sendWorkflow(REPO_FE, 1001, 902, 1, shaFixGreen, "success", T0.plusSeconds(260))).isEqualTo(204);
        assertThat(sendWorkflow(REPO_FE, 1001, 901, 2, shaBeforePush, "failure", T0.plusSeconds(300))).isEqualTo(204);
        mvc.perform(get("/api/v1/gitci/workspaces/{ws}/task-summaries", wsA)
                        .param("taskIds", task1.toString()).header("Authorization", bearer(userA1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].ci.status").value("SUCCESS"))
                .andExpect(jsonPath("$.items[0].ci.staleForLatestCommit").value(false));

        // API-10: Another task T-002 commits on the SAME reused branch feature/reuse and fails CI -> T-001 stays SUCCESS
        assertThat(sendPush(REPO_FE, 1001, "feature/reuse", List.of(
                Map.of("id", shaReusedBranchOtherTask, "message", "T-002 reuse branch", "timestamp", T0.plusSeconds(400).toString())
        ))).isEqualTo(204);
        assertThat(sendWorkflow(REPO_FE, 1001, 903, 1, shaReusedBranchOtherTask, "failure", T0.plusSeconds(420))).isEqualTo(204);
        mvc.perform(get("/api/v1/gitci/workspaces/{ws}/task-summaries", wsA)
                        .param("taskIds", task1 + "," + task2).header("Authorization", bearer(userA1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].taskId").value(task1.toString()))
                .andExpect(jsonPath("$.items[0].ci.status").value("SUCCESS"))
                .andExpect(jsonPath("$.items[1].taskId").value(task2.toString()))
                .andExpect(jsonPath("$.items[1].ci.status").value("FAILED"));
    }

    private int sendWebhook(UUID repoId, String event, Map<String, Object> payload) throws Exception {
        byte[] body = json.writeValueAsBytes(payload);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secretFor(repoId).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String sig = "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/gitci/webhook/github?repositoryId=" + repoId))
                .header("Content-Type", "application/json")
                .header("X-Hub-Signature-256", sig)
                .header("X-GitHub-Delivery", UUID.randomUUID().toString())
                .header("X-GitHub-Event", event)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode();
    }
}
