package io.devflow.auth.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.devflow.auth.internal.security.RateLimiterService;
import io.devflow.auth.internal.security.StompSessionRegistry;
import io.devflow.board.internal.AccountDeletionEventListener;
import io.devflow.common.event.DeletedUser;
import io.devflow.common.event.UserAccountDeletedEvent;
import io.devflow.common.event.WorkspaceDeletedEvent;
import io.devflow.common.security.AuthenticatedActor;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * T-009: account deletion through the public REST API, on PostgreSQL 17 with the Flyway schema, including the
 * Board cleanup that runs on {@code user.account_deleted} / {@code workspace.deleted}.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none",
        "spring.ai.model.image=none",
        "spring.ai.model.moderation=none",
        "spring.ai.model.audio.speech=none",
        "spring.ai.model.audio.transcription=none"
})
@AutoConfigureMockMvc
@RecordApplicationEvents
@Import(AccountDeletionApiIntegrationTest.McpProbeController.class)
@Testcontainers
class AccountDeletionApiIntegrationTest {

    private static final String PASSWORD = "Passw0rd!Delete";
    private static final String PROBE = "/mcp/test/deleted-whoami";
    private static final AtomicInteger IP_SUFFIX = new AtomicInteger(1);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("devflow")
            .withUsername("devflow")
            .withPassword("devflow");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }

    /**
     * Test-only endpoint under the MCP path prefix, where personal access tokens authenticate.
     */
    @RestController
    static class McpProbeController {

        @GetMapping(PROBE)
        Map<String, Object> whoami(@AuthenticationPrincipal AuthenticatedActor actor) {
            return Map.of("userId", actor.userId().toString());
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private RateLimiterService rateLimiterService;

    @Autowired
    private StompSessionRegistry stompSessionRegistry;

    @Autowired
    private AccountDeletionEventListener boardListener;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private ApplicationEvents applicationEvents;

    private String passwordHash;

    @BeforeEach
    void setUp() {
        rateLimiterService.clearAll();
        if (passwordHash == null) {
            passwordHash = passwordEncoder.encode(PASSWORD);
        }
    }

    // --- Re-authentication and validation ---

    @Test
    void wrongOrMissingPasswordIsRejectedAndNothingIsDeleted() throws Exception {
        TestUser user = user("wrong-password");
        String access = login(user).get("accessToken").asText();

        deleteAccount(access, Map.of("password", "Not-the-password1"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("REAUTHENTICATION_FAILED"));
        deleteAccount(access, Map.of())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.password").exists());
        // 73 UTF-8 bytes: refused by validation instead of reaching BCrypt.
        deleteAccount(access, Map.of("password", "Aa1" + "é".repeat(35)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(delete("/api/v1/auth/me").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("password", PASSWORD))))
                .andExpect(status().isUnauthorized());

        assertThat(userCount(user.id())).isEqualTo(1);
        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
                .andExpect(status().isOk());
    }

    @Test
    void reauthenticationIsRateLimitedPerUser() throws Exception {
        TestUser user = user("guesser");
        String access = login(user).get("accessToken").asText();

        for (int i = 0; i < 5; i++) {
            deleteAccount(access, Map.of("password", "Guess-number-" + i)).andExpect(status().isForbidden());
        }
        deleteAccount(access, Map.of("password", PASSWORD))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"));
        assertThat(userCount(user.id())).isEqualTo(1);
    }

    // --- Workspace ownership ---

    @Test
    void ownerOfASharedWorkspaceMustTransferOwnershipFirst() throws Exception {
        TestUser owner = user("shared-owner");
        TestUser member = user("shared-member");
        TestUser outsider = user("shared-outsider");
        UUID workspace = workspace(owner, "Shared team");
        member(workspace, member, "MEMBER");
        String ownerAccess = login(owner).get("accessToken").asText();
        String memberAccess = login(member).get("accessToken").asText();

        deleteAccount(ownerAccess, Map.of("password", PASSWORD))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("WORKSPACE_OWNERSHIP_TRANSFER_REQUIRED"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("transfer-ownership")))
                .andExpect(jsonPath("$.workspaces[0].id").value(workspace.toString()))
                .andExpect(jsonPath("$.workspaces[0].name").value("Shared team"))
                .andExpect(jsonPath("$.workspaces[0].otherMembers").value(1));
        assertThat(userCount(owner.id())).isEqualTo(1);

        transfer(memberAccess, workspace, member.id()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").exists());
        transfer(ownerAccess, workspace, outsider.id()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.newOwnerId").exists());
        transfer(ownerAccess, workspace, owner.id()).andExpect(status().isBadRequest());
        transfer(ownerAccess, UUID.randomUUID(), member.id()).andExpect(status().isForbidden());
        transfer(ownerAccess, workspace, member.id()).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(workspace.toString()))
                .andExpect(jsonPath("$.role").value("ADMIN"));
        assertThat(jdbc.queryForObject("SELECT owner_id FROM workspaces WHERE id = ?", UUID.class, workspace))
                .isEqualTo(member.id());
        assertThat(role(workspace, member.id())).isEqualTo("OWNER");
        assertThat(role(workspace, owner.id())).isEqualTo("ADMIN");

        deleteAccount(ownerAccess, Map.of("password", PASSWORD)).andExpect(status().isNoContent());
        assertThat(userCount(owner.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workspace_members WHERE workspace_id = ?",
                Long.class, workspace)).isEqualTo(1);
    }

    // --- Full erasure ---

    @Test
    void deletionErasesPersonalDataRevokesEverySessionAndAnonymisesBoardData() throws Exception {
        TestUser user = user("leaver");
        TestUser teammate = user("teammate");
        JsonNode session = login(user);
        String access = session.get("accessToken").asText();
        String refresh = session.get("refreshToken").asText();
        String pat = readJson(mockMvc.perform(post("/api/v1/auth/tokens")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("name", "Cursor"))))
                .andExpect(status().isCreated())).get("token").asText();
        mcpProbe(pat).andExpect(status().isOk());
        WebSocketSession socket = openStompSocket(user.id());

        // A workspace the user is alone in: deleted with the account, Board data included.
        UUID soleWorkspace = workspace(user, "Solo");
        BoardRows solo = boardRows(soleWorkspace, user.id(), user.id());
        // A teammate's workspace the user belongs to: kept, with the user's traces anonymised.
        UUID teamWorkspace = workspace(teammate, "Team");
        member(teamWorkspace, user, "MEMBER");
        BoardRows team = boardRows(teamWorkspace, user.id(), user.id());
        UUID teammateTask = task(team.columnId(), teammate.id());
        UUID teammateComment = comment(teammateTask, teammate.id(), "Teammate note");
        UUID teammateAudit = audit(teamWorkspace, team.boardId(), teammate.id(), "198.51.100.9");
        long taskVersion = jdbc.queryForObject("SELECT version FROM tasks WHERE id = ?", Long.class, team.taskId());
        // Invitations addressed to the user's email, and one the user sent.
        UUID invitedElsewhere = invitation(workspace(teammate, "Elsewhere"), user.email(), null, "invite-" + user.id());
        UUID sentByUser = invitation(teamWorkspace, "friend-" + user.id() + "@devflow.test", user.id(),
                "sent-" + user.id());
        jdbc.update("INSERT INTO verification_tokens(user_id, token_hash, type, expires_at) "
                + "VALUES (?, ?, 'EMAIL_VERIFICATION', now() + interval '1 hour')", user.id(), "a".repeat(63) + "1");
        double successBefore = counter("devflow.auth.account_deleted", "success");

        mockMvc.perform(delete("/api/v1/auth/me")
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.45");
                            return request;
                        })
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("password", PASSWORD))))
                .andExpect(status().isNoContent());

        // Signed out everywhere: password login, access token, refresh token, personal access token, open socket.
        login(user.email(), PASSWORD).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/auth/workspaces").header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("refreshToken", refresh))))
                .andExpect(status().isUnauthorized());
        mcpProbe(pat).andExpect(status().isUnauthorized());
        verify(socket).close(argThat(closeStatus -> closeStatus.getCode() == CloseStatus.POLICY_VIOLATION.getCode()));

        // No email or name left anywhere in the database.
        assertThat(columnsContaining(user.email())).isEmpty();
        assertThat(columnsContaining(user.fullName())).isEmpty();
        assertThat(userCount(user.id())).isZero();
        assertThat(countWhere("workspace_members", "user_id", user.id())).isZero();
        assertThat(countWhere("verification_tokens", "user_id", user.id())).isZero();
        assertThat(countWhere("personal_access_tokens", "user_id", user.id())).isZero();
        assertThat(countWhere("workspace_invitations", "id", invitedElsewhere)).isZero();
        assertThat(jdbc.queryForObject("SELECT invited_by FROM workspace_invitations WHERE id = ?", UUID.class,
                sentByUser)).isNull();

        // Sole workspace and its Board data are gone.
        assertThat(countWhere("workspaces", "id", soleWorkspace)).isZero();
        assertThat(countWhere("boards", "id", solo.boardId())).isZero();
        assertThat(countWhere("tasks", "id", solo.taskId())).isZero();
        assertThat(countWhere("task_comments", "id", solo.commentId())).isZero();
        assertThat(countWhere("board_audit_events", "id", solo.auditId())).isZero();

        // In the team workspace: unassigned, author/actor anonymised, content and other people's data untouched.
        Map<String, Object> task = jdbc.queryForMap("SELECT assignee_id, version FROM tasks WHERE id = ?", team.taskId());
        assertThat(task.get("assignee_id")).isNull();
        assertThat((Long) task.get("version")).isEqualTo(taskVersion + 1);
        assertThat(jdbc.queryForObject("SELECT assignee_id FROM tasks WHERE id = ?", UUID.class, teammateTask))
                .isEqualTo(teammate.id());
        Map<String, Object> comment = jdbc.queryForMap("SELECT author_id, content FROM task_comments WHERE id = ?",
                team.commentId());
        assertThat(comment.get("author_id")).isEqualTo(DeletedUser.ID);
        assertThat(comment.get("content")).isEqualTo("Comment by " + user.id());
        assertThat(jdbc.queryForObject("SELECT author_id FROM task_comments WHERE id = ?", UUID.class,
                teammateComment)).isEqualTo(teammate.id());
        Map<String, Object> audit = jdbc.queryForMap("SELECT actor_id, client_ip FROM board_audit_events WHERE id = ?",
                team.auditId());
        assertThat(audit.get("actor_id")).isEqualTo(DeletedUser.ID);
        assertThat(audit.get("client_ip")).isNull();
        assertThat(jdbc.queryForObject("SELECT client_ip FROM board_audit_events WHERE id = ?", String.class,
                teammateAudit)).isEqualTo("198.51.100.9");

        // Audited without PII: id, time, truncated IP.
        Map<String, Object> record = jdbc.queryForMap("SELECT * FROM account_deletion_audit WHERE user_id = ?",
                user.id());
        assertThat(record.get("client_ip_masked")).isEqualTo("203.0.113.0");
        assertThat(record.get("workspaces_deleted")).isEqualTo(1);
        assertThat(record.get("deleted_at")).isNotNull();

        assertThat(applicationEvents.stream(UserAccountDeletedEvent.class))
                .singleElement()
                .satisfies(event -> assertThat(event.userId()).isEqualTo(user.id()));
        assertThat(applicationEvents.stream(WorkspaceDeletedEvent.class))
                .singleElement()
                .satisfies(event -> assertThat(event.workspaceId()).isEqualTo(soleWorkspace));
        assertThat(counter("devflow.auth.account_deleted", "success")).isEqualTo(successBefore + 1);
    }

    @Test
    void boardCleanupIsIdempotent() throws Exception {
        TestUser user = user("replayed");
        TestUser teammate = user("replay-teammate");
        UUID teamWorkspace = workspace(teammate, "Replay team");
        member(teamWorkspace, user, "MEMBER");
        BoardRows team = boardRows(teamWorkspace, user.id(), user.id());
        UUID soleWorkspace = workspace(user, "Replay solo");
        BoardRows solo = boardRows(soleWorkspace, user.id(), user.id());
        String access = login(user).get("accessToken").asText();
        deleteAccount(access, Map.of("password", PASSWORD)).andExpect(status().isNoContent());
        Map<String, Object> taskAfterFirst = jdbc.queryForMap("SELECT * FROM tasks WHERE id = ?", team.taskId());

        // Re-delivery (or a manual replay) changes nothing and does not fail.
        boardListener.on(new UserAccountDeletedEvent(user.id(), Instant.now()));
        boardListener.on(new WorkspaceDeletedEvent(soleWorkspace, Instant.now()));

        assertThat(jdbc.queryForMap("SELECT * FROM tasks WHERE id = ?", team.taskId())).isEqualTo(taskAfterFirst);
        assertThat(jdbc.queryForObject("SELECT author_id FROM task_comments WHERE id = ?", UUID.class,
                team.commentId())).isEqualTo(DeletedUser.ID);
        assertThat(countWhere("boards", "id", solo.boardId())).isZero();
        assertThat(counter("devflow.board.account_cleanup", "failed", "kind", "user")).isZero();
    }

    // --- Concurrency ---

    @Test
    void deletionRacingAnInvitationAcceptanceNeverDeadlocks() throws Exception {
        for (int round = 0; round < 8; round++) {
            TestUser owner = user("race-owner-" + round);
            TestUser invitee = user("race-invitee-" + round);
            UUID workspace = workspace(owner, "Race " + round);
            String rawToken = "race-token-" + UUID.randomUUID();
            invitation(workspace, invitee.email(), owner.id(), rawToken);
            String ownerAccess = login(owner).get("accessToken").asText();
            String inviteeAccess = login(invitee).get("accessToken").asText();

            // Both lock the owner's/invitee's user row, then the workspace, then the invitation rows.
            List<Integer> statuses = concurrently(List.of(
                    () -> statusOf(deleteAccount(ownerAccess, Map.of("password", PASSWORD))),
                    () -> statusOf(mockMvc.perform(post("/api/v1/auth/invitations/accept")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + inviteeAccess)
                            .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("token", rawToken)))))));

            if (statuses.get(0) == 204) {
                // Deleted first: the workspace and its invitation went with the account.
                assertThat(statuses.get(1)).as("accept, round %d", round).isEqualTo(400);
                assertThat(countWhere("workspaces", "id", workspace)).isZero();
            } else {
                // Accepted first: the workspace now has another member, so deletion needs a transfer.
                assertThat(statuses).as("round %d", round).containsExactly(409, 200);
                assertThat(userCount(owner.id())).isEqualTo(1);
            }
        }
    }

    // --- Helpers ---

    private record TestUser(UUID id, String email, String fullName) {}

    private record BoardRows(UUID boardId, UUID columnId, UUID taskId, UUID commentId, UUID auditId) {}

    private TestUser user(String label) {
        UUID id = UUID.randomUUID();
        String email = label + "-" + id + "@devflow.test";
        String fullName = "T-009 " + label + " " + id.toString().substring(0, 8);
        jdbc.update("INSERT INTO users(id, email, password_hash, full_name, status, email_verified_at) "
                + "VALUES (?, ?, ?, ?, 'ACTIVE', now())", id, email, passwordHash, fullName);
        return new TestUser(id, email, fullName);
    }

    private UUID workspace(TestUser owner, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO workspaces(id, name, slug, owner_id) VALUES (?, ?, ?, ?)",
                id, name, "ws-" + id, owner.id());
        member(id, owner, "OWNER");
        return id;
    }

    private void member(UUID workspaceId, TestUser user, String role) {
        jdbc.update("INSERT INTO workspace_members(workspace_id, user_id, role) VALUES (?, ?, ?)",
                workspaceId, user.id(), role);
    }

    private UUID invitation(UUID workspaceId, String email, UUID invitedBy, String rawToken) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO workspace_invitations(id, workspace_id, email, token_hash, invited_by, expires_at) "
                + "VALUES (?, ?, ?, encode(sha256(convert_to(?, 'UTF8')), 'hex'), ?, now() + interval '1 day')",
                id, workspaceId, email, rawToken, invitedBy);
        return id;
    }

    /** A board with one column, one task assigned to {@code assignee}, one comment and one audit row. */
    private BoardRows boardRows(UUID workspaceId, UUID assignee, UUID actor) {
        UUID board = UUID.randomUUID();
        jdbc.update("INSERT INTO boards(id, workspace_id, name) VALUES (?, ?, 'Board')", board, workspaceId);
        UUID column = UUID.randomUUID();
        jdbc.update("INSERT INTO columns(id, board_id, name, position, status_category) VALUES (?, ?, 'To do', 0, 'TODO')",
                column, board);
        UUID task = task(column, assignee);
        UUID comment = comment(task, actor, "Comment by " + actor);
        UUID audit = audit(workspaceId, board, actor, "203.0.113.77");
        return new BoardRows(board, column, task, comment, audit);
    }

    private UUID task(UUID columnId, UUID assignee) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tasks(id, column_id, title, position, assignee_id) VALUES (?, ?, 'Task', 0, ?)",
                id, columnId, assignee);
        return id;
    }

    private UUID comment(UUID taskId, UUID authorId, String content) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO task_comments(id, task_id, author_id, content) VALUES (?, ?, ?, ?)",
                id, taskId, authorId, content);
        return id;
    }

    private UUID audit(UUID workspaceId, UUID boardId, UUID actorId, String clientIp) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO board_audit_events(id, actor_id, action, resource_type, resource_id, workspace_id, "
                        + "correlation_id, client_ip, changed_fields) VALUES (?, ?, 'CREATE', 'BOARD', ?, ?, ?, ?, 'name')",
                id, actorId, boardId, workspaceId, UUID.randomUUID(), clientIp);
        return id;
    }

    /**
     * Registers an open WebSocket as if the user had sent an authenticated STOMP CONNECT on this instance.
     */
    private WebSocketSession openStompSocket(UUID userId) throws Exception {
        WebSocketSession socket = mock(WebSocketSession.class);
        String sessionId = "ws-" + UUID.randomUUID();
        when(socket.getId()).thenReturn(sessionId);
        when(socket.isOpen()).thenReturn(true);
        stompSessionRegistry.decorate(mock(WebSocketHandler.class)).afterConnectionEstablished(socket);
        stompSessionRegistry.bind(userId, sessionId);
        return socket;
    }

    /** Every text column in the public schema that still holds {@code value}, as "table.column". */
    private List<String> columnsContaining(String value) {
        List<String> hits = new ArrayList<>();
        List<Map<String, Object>> columns = jdbc.queryForList("SELECT table_name, column_name "
                + "FROM information_schema.columns WHERE table_schema = 'public' "
                + "AND data_type IN ('character varying', 'text') AND table_name <> 'flyway_schema_history'");
        for (Map<String, Object> column : columns) {
            String table = (String) column.get("table_name");
            String name = (String) column.get("column_name");
            Long count = jdbc.queryForObject("SELECT COUNT(*) FROM \"" + table + "\" WHERE \"" + name
                    + "\" ILIKE '%' || ? || '%'", Long.class, value);
            if (count != null && count > 0) {
                hits.add(table + "." + name);
            }
        }
        return hits;
    }

    private long userCount(UUID id) {
        return countWhere("users", "id", id);
    }

    private long countWhere(String table, String column, UUID value) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?", Long.class, value);
    }

    private String role(UUID workspaceId, UUID userId) {
        return jdbc.queryForObject("SELECT role FROM workspace_members WHERE workspace_id = ? AND user_id = ?",
                String.class, workspaceId, userId);
    }

    private double counter(String name, String result, String... tags) {
        var search = meterRegistry.find(name).tag("result", result);
        for (int i = 0; i + 1 < tags.length; i += 2) {
            search = search.tag(tags[i], tags[i + 1]);
        }
        var counter = search.counter();
        return counter == null ? 0 : counter.count();
    }

    private JsonNode login(TestUser user) throws Exception {
        return readJson(login(user.email(), PASSWORD).andExpect(status().isOk()));
    }

    private ResultActions login(String email, String password) throws Exception {
        String ip = nextIp();
        return mockMvc.perform(post("/api/v1/auth/login")
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("email", email, "password", password))));
    }

    private ResultActions deleteAccount(String accessToken, Object body) throws Exception {
        return mockMvc.perform(delete("/api/v1/auth/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(body)));
    }

    private ResultActions transfer(String accessToken, UUID workspaceId, UUID newOwnerId) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/workspaces/{id}/transfer-ownership", workspaceId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("newOwnerId", newOwnerId))));
    }

    private ResultActions mcpProbe(String rawToken) throws Exception {
        return mockMvc.perform(get(PROBE).header(HttpHeaders.AUTHORIZATION, "Bearer " + rawToken));
    }

    private static String nextIp() {
        int n = IP_SUFFIX.getAndIncrement();
        return "198.18." + (n / 250) + "." + (n % 250 + 1);
    }

    /**
     * Runs the calls on separate threads released at the same instant; returns results in call order.
     */
    private static List<Integer> concurrently(List<Callable<Integer>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> call : calls) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return call.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Integer> results = new ArrayList<>();
            for (Future<Integer> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static int statusOf(ResultActions result) {
        return result.andReturn().getResponse().getStatus();
    }

    private JsonNode readJson(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}
