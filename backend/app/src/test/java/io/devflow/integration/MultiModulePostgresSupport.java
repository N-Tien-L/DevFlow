package io.devflow.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.devflow.auth.internal.security.JwtTokenProvider;
import io.devflow.gitci.internal.GitDomainEventOutbox;
import io.micrometer.core.instrument.MeterRegistry;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared real PostgreSQL 17 + Flyway + HTTP/HMAC + STOMP infrastructure for the T-041
 * multi-module integration test suite inside {@code :app}.
 *
 * <p>Uses a single shared Testcontainers PostgreSQL 17 instance per test JVM so Spring
 * TestContext caching keeps full-monolith integration runs fast while every test starts
 * with an isolated, deterministically cleaned database state.
 */
public abstract class MultiModulePostgresSupport {

    protected static final UUID REPO_WS_A = UUID.fromString("a1000041-1111-4111-8111-111111110001");
    protected static final UUID REPO_WS_A_ALT = UUID.fromString("a1000041-1111-4111-8111-111111110002");
    protected static final UUID REPO_WS_B = UUID.fromString("a1000041-2222-4222-8222-222222220001");
    protected static final UUID REPO_CI = UUID.fromString("a1000041-3333-4333-8333-333333330001");

    protected static final String REMOTE_REPO_A = "41001";
    protected static final String REMOTE_REPO_A_ALT = "41002";
    protected static final String REMOTE_REPO_B = "42001";
    protected static final String REMOTE_REPO_CI = "41003";

    protected static final String SECRET_A_CURRENT = "t041-hmac-secret-ws-a-current-0123456789abcdef";
    protected static final String SECRET_A_PREVIOUS_VALID = "t041-hmac-secret-ws-a-prev-valid-0123456789";
    protected static final String SECRET_A_ALT_CURRENT = "t041-hmac-secret-ws-a-alt-current-0123456789";
    protected static final String SECRET_A_ALT_PREVIOUS_EXPIRED = "t041-hmac-secret-ws-a-alt-prev-expired-9999";
    protected static final String SECRET_B_CURRENT = "t041-hmac-secret-ws-b-current-0123456789abcdef";
    protected static final String SECRET_CI_CURRENT = "t041-hmac-secret-ci-current-0123456789abcdef";

    protected static final String GITHUB_PAT_CANARY = "github_pat_t041_canary_never_leak_9999";
    protected static final String SIGNED_URL_CANARY =
            "https://results-receiver.actions.githubusercontent.com/log?sig=t041_secret_sig_888";
    protected static final String EMAIL_CANARY = "private-dev@canary.local";
    protected static final String XSS_CANARY = "<script>alert('t041')</script>";
    protected static final Instant BASE_TIME = Instant.parse("2026-11-28T08:00:00Z");

    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("devflow_t041")
            .withUsername("devflow")
            .withPassword("devflow_t041_test");

    static {
        POSTGRES.start();
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper json;

    @Autowired
    protected JwtTokenProvider tokens;

    @Autowired
    protected ApplicationEventPublisher events;

    @Autowired
    protected GitDomainEventOutbox gitOutbox;

    @Autowired
    protected PlatformTransactionManager txManager;

    @Autowired
    protected MeterRegistry metrics;

    @Autowired
    protected SimpUserRegistry simpUserRegistry;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    protected final HttpClient httpClient = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void registerMultiModuleProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("devflow.gitci.github-webhook.enabled", () -> "true");
        registry.add("devflow.gitci.github-webhook.max-body-bytes", () -> "65536");

        String prefixA = "devflow.gitci.github-webhook.bindings[" + REPO_WS_A + "].";
        registry.add(prefixA + "reference", () -> "test:t041:repo-a");
        registry.add(prefixA + "current", () -> SECRET_A_CURRENT);
        registry.add(prefixA + "previous", () -> SECRET_A_PREVIOUS_VALID);
        registry.add(prefixA + "previous-valid-until", () -> "2099-01-01T00:00:00Z");

        String prefixAlt = "devflow.gitci.github-webhook.bindings[" + REPO_WS_A_ALT + "].";
        registry.add(prefixAlt + "reference", () -> "test:t041:repo-a-alt");
        registry.add(prefixAlt + "current", () -> SECRET_A_ALT_CURRENT);
        registry.add(prefixAlt + "previous", () -> SECRET_A_ALT_PREVIOUS_EXPIRED);
        registry.add(prefixAlt + "previous-valid-until", () -> "2020-01-01T00:00:00Z");

        String prefixB = "devflow.gitci.github-webhook.bindings[" + REPO_WS_B + "].";
        registry.add(prefixB + "reference", () -> "test:t041:repo-b");
        registry.add(prefixB + "current", () -> SECRET_B_CURRENT);

        String prefixCi = "devflow.gitci.github-webhook.bindings[" + REPO_CI + "].";
        registry.add(prefixCi + "reference", () -> "test:t041:repo-ci");
        registry.add(prefixCi + "current", () -> SECRET_CI_CURRENT);
    }

    @AfterEach
    void clearThreadLocals() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    protected void cleanDatabase() {
        SecurityContextHolder.clearContext();
        MDC.clear();
        jdbc.update("DELETE FROM repositories");
        jdbc.update("DELETE FROM ai_ci_summaries");
        jdbc.update("DELETE FROM notifications");
        jdbc.update("DELETE FROM board_git_event_receipts");
        jdbc.update("DELETE FROM board_audit_events");
        jdbc.update("DELETE FROM board_account_cleanup_jobs");
        jdbc.update("DELETE FROM account_deletion_audit");
        jdbc.update("DELETE FROM boards");
        jdbc.update("DELETE FROM board_task_key_counters");
        jdbc.update("DELETE FROM workspace_members");
        jdbc.update("DELETE FROM workspaces");
        jdbc.update("DELETE FROM users");
    }

    protected TenantFixture seedStandardFixture() {
        cleanDatabase();
        UUID ownerA = insertUser("owner-a", null);
        UUID assigneeA = insertUser("assignee-a", null);
        UUID assigneeA2 = insertUser("assignee-a2", null);
        UUID collabA = insertUser("collab-a", null);
        UUID revokedA = insertUser("revoked-a", null);
        UUID ownerB = insertUser("owner-b", null);
        UUID assigneeB = insertUser("assignee-b", null);

        UUID wsA = insertWorkspace(ownerA, "Workspace A");
        addMember(wsA, ownerA, "OWNER");
        addMember(wsA, assigneeA, "MEMBER");
        addMember(wsA, assigneeA2, "MEMBER");
        addMember(wsA, collabA, "MEMBER");
        addMember(wsA, revokedA, "MEMBER");

        UUID wsB = insertWorkspace(ownerB, "Workspace B");
        addMember(wsB, ownerB, "OWNER");
        addMember(wsB, assigneeB, "MEMBER");

        UUID boardA = insertBoard(wsA, "Sprint Board A", false);
        Map<String, UUID> colsA = insertStandardColumns(boardA);

        UUID boardArchived = insertBoard(wsA, "Archived Board A", true);
        Map<String, UUID> colsArchived = insertStandardColumns(boardArchived);

        UUID boardB = insertBoard(wsB, "Sprint Board B", false);
        Map<String, UUID> colsB = insertStandardColumns(boardB);

        // Task numbers in WS-A: T-001 .. T-005
        UUID taskA1 = insertTask(colsA.get("TODO"), "Task A1 Core Flow", 0, assigneeA);
        UUID taskA2 = insertTask(colsA.get("TODO"), "Task A2 Secondary", 1, assigneeA2);
        UUID taskUnassigned = insertTask(colsA.get("TODO"), "Task A3 Unassigned", 2, null);
        UUID taskRevoked = insertTask(colsA.get("TODO"), "Task A4 Revoked Assignee", 3, revokedA);
        UUID taskArchived = insertTask(colsArchived.get("TODO"), "Task A5 Archived", 0, assigneeA);

        // Task number in WS-B: T-001 (same display key as Task A1 in WS-A!)
        UUID taskB1 = insertTask(colsB.get("TODO"), "Task B1 Foreign Tenant", 0, assigneeB);

        insertRepository(REPO_WS_A, wsA, REMOTE_REPO_A, "devflow-test/app-a",
                "https://github.com/devflow-test/app-a", "test:t041:repo-a");
        insertRepository(REPO_WS_A_ALT, wsA, REMOTE_REPO_A_ALT, "devflow-test/app-a-alt",
                "https://github.com/devflow-test/app-a-alt", "test:t041:repo-a-alt");
        insertRepository(REPO_WS_B, wsB, REMOTE_REPO_B, "devflow-test/app-b",
                "https://github.com/devflow-test/app-b", "test:t041:repo-b");
        insertRepository(REPO_CI, wsA, REMOTE_REPO_CI, "devflow-test/app-ci",
                "https://github.com/devflow-test/app-ci", "test:t041:repo-ci");

        return new TenantFixture(
                wsA, wsB,
                ownerA, assigneeA, assigneeA2, collabA, revokedA, ownerB, assigneeB,
                boardA, boardArchived, boardB,
                colsA, colsArchived, colsB,
                taskA1, taskA2, taskUnassigned, taskRevoked, taskArchived, taskB1);
    }

    protected UUID insertUser(String prefix, String rawPassword) {
        UUID id = UUID.randomUUID();
        String hash = rawPassword == null ? "$2a$04$t041syntheticplaceholderhash000000000000000000000000000"
                : passwordEncoder.encode(rawPassword);
        jdbc.update("""
                INSERT INTO users(id, email, password_hash, full_name, status, email_verified_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP)
                """, id, prefix + "-" + id + "@devflow.test", hash, "User " + prefix);
        return id;
    }

    protected UUID insertWorkspace(UUID ownerId, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO workspaces(id, name, slug, owner_id) VALUES (?, ?, ?, ?)",
                id, name, "ws-" + id, ownerId);
        return id;
    }

    protected void addMember(UUID workspaceId, UUID userId, String role) {
        jdbc.update("INSERT INTO workspace_members(workspace_id, user_id, role) VALUES (?, ?, ?)",
                workspaceId, userId, role);
    }

    protected UUID insertBoard(UUID workspaceId, String name, boolean archived) {
        return jdbc.queryForObject(
                "INSERT INTO boards(workspace_id, name, is_archived) VALUES (?, ?, ?) RETURNING id",
                UUID.class, workspaceId, name, archived);
    }

    protected Map<String, UUID> insertStandardColumns(UUID boardId) {
        Map<String, UUID> result = new LinkedHashMap<>();
        List<String> categories = List.of("TODO", "IN_PROGRESS", "IN_REVIEW", "DONE");
        List<String> names = List.of("To Do", "In Progress", "In Review", "Done");
        for (int i = 0; i < categories.size(); i++) {
            UUID colId = jdbc.queryForObject(
                    "INSERT INTO columns(board_id, name, position, status_category) VALUES (?, ?, ?, ?) RETURNING id",
                    UUID.class, boardId, names.get(i), i, categories.get(i));
            result.put(categories.get(i), colId);
        }
        return result;
    }

    protected UUID insertTask(UUID columnId, String title, int position, UUID assigneeId) {
        return jdbc.queryForObject("""
                INSERT INTO tasks(column_id, title, description, position, assignee_id, priority)
                VALUES (?, ?, 'Fixture description', ?, ?, 'MEDIUM') RETURNING id
                """, UUID.class, columnId, title, position, assigneeId);
    }

    protected void insertRepository(
            UUID repoId, UUID workspaceId, String remoteRepoId, String name, String url, String secretRef) {
        jdbc.update("""
                INSERT INTO repositories(id, workspace_id, provider, remote_repo_id, name, repository_url, webhook_secret_ref)
                VALUES (?, ?, 'GITHUB', ?, ?, ?, ?)
                """, repoId, workspaceId, remoteRepoId, name, url, secretRef);
    }

    protected String bearer(UUID userId) {
        return bearer(userId, 0);
    }

    protected String bearer(UUID userId, int tokenVersion) {
        return "Bearer " + tokens.generateAccessToken(userId, userId + "@devflow.test", tokenVersion);
    }

    protected long countRows(String table) {
        if (!table.matches("[a-z_]+")) {
            throw new IllegalArgumentException("Unsafe table name: " + table);
        }
        Long count = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return count == null ? 0L : count;
    }

    protected Map<String, Object> taskRow(UUID taskId) {
        return jdbc.queryForMap("SELECT * FROM tasks WHERE id = ?", taskId);
    }

    protected List<Map<String, Object>> notificationRows(UUID recipientId) {
        return jdbc.queryForList(
                "SELECT * FROM notifications WHERE recipient_id = ? ORDER BY created_at ASC, id ASC",
                recipientId);
    }

    protected HttpResponse<String> sendSignedWebhook(
            UUID repoId, String secret, String eventName, String deliveryId, Object payload) throws Exception {
        byte[] body = json.writeValueAsBytes(payload);
        return sendRawWebhook(repoId, signHmac(body, secret), eventName, deliveryId, "application/json", body);
    }

    protected HttpResponse<String> sendRawWebhook(
            UUID repoId, String signatureHeader, String eventName, String deliveryId,
            String contentType, byte[] body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/v1/gitci/webhook/github?repositoryId=" + repoId))
                .timeout(Duration.ofSeconds(10));
        if (contentType != null) {
            builder.header("Content-Type", contentType);
        }
        if (signatureHeader != null) {
            builder.header("X-Hub-Signature-256", signatureHeader);
        }
        if (deliveryId != null) {
            builder.header("X-GitHub-Delivery", deliveryId);
        }
        if (eventName != null) {
            builder.header("X-GitHub-Event", eventName);
        }
        return httpClient.send(builder.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    protected static String signHmac(byte[] body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }

    protected static String hexSha(char digit, int suffix) {
        String tail = String.format("%08x", suffix);
        return String.valueOf(digit).repeat(32) + tail;
    }

    protected static Map<String, Object> pushPayload(
            long remoteRepoId, String branch, String sha, String message, Instant committedAt, String authorUsername) {
        Map<String, Object> author = new LinkedHashMap<>();
        if (authorUsername != null) {
            author.put("username", authorUsername);
        }
        author.put("email", EMAIL_CANARY);
        Map<String, Object> commit = new LinkedHashMap<>();
        commit.put("id", sha);
        commit.put("message", message);
        commit.put("timestamp", committedAt.toString());
        commit.put("author", author);
        return Map.of(
                "repository", Map.of("id", remoteRepoId),
                "ref", "refs/heads/" + branch,
                "after", sha,
                "deleted", false,
                "commits", List.of(commit));
    }

    protected static Map<String, Object> prPayload(
            long remoteRepoId, String action, int number, String title, String headBranch, String baseBranch,
            String state, boolean merged, Instant updatedAt) {
        Map<String, Object> pr = new LinkedHashMap<>();
        pr.put("number", number);
        pr.put("title", title);
        pr.put("head", Map.of("ref", headBranch, "repo", Map.of("id", remoteRepoId)));
        pr.put("base", Map.of("ref", baseBranch, "repo", Map.of("id", remoteRepoId)));
        pr.put("state", state);
        pr.put("merged", merged);
        pr.put("updated_at", updatedAt.toString());
        if ("opened".equals(action)) {
            pr.put("created_at", updatedAt.toString());
        }
        if (merged) {
            pr.put("merged_at", updatedAt.toString());
        }
        return Map.of(
                "repository", Map.of("id", remoteRepoId),
                "action", action,
                "number", number,
                "pull_request", pr);
    }

    protected static Map<String, Object> workflowRunPayload(
            long remoteRepoId, String action, long runId, int attempt, String sha, String branch,
            String status, String conclusion, Instant startedAt, Instant updatedAt) {
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("id", runId);
        run.put("run_attempt", attempt);
        run.put("head_sha", sha);
        run.put("head_branch", branch);
        run.put("status", status);
        run.put("conclusion", conclusion);
        run.put("run_started_at", startedAt.toString());
        run.put("updated_at", updatedAt.toString());
        run.put("repository", Map.of("id", remoteRepoId));
        return Map.of(
                "repository", Map.of("id", remoteRepoId),
                "action", action,
                "workflow_run", run);
    }

    protected static void awaitCondition(Duration timeout, BooleanSupplier condition, String failureMessage)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(25);
        }
        assertThat(condition.getAsBoolean()).as(failureMessage).isTrue();
    }

    protected DualChannelStompClient connectStomp(UUID userId) throws Exception {
        return connectStomp(userId, 0);
    }

    protected DualChannelStompClient connectStomp(UUID userId, int tokenVersion) throws Exception {
        return new DualChannelStompClient(userId, tokenVersion);
    }

    protected final class DualChannelStompClient implements AutoCloseable {
        private final UUID userId;
        private final WebSocketStompClient client;
        private final StompSession session;
        private final BlockingQueue<Map<?, ?>> boardFrames = new LinkedBlockingQueue<>();
        private final BlockingQueue<Map<?, ?>> notificationFrames = new LinkedBlockingQueue<>();
        private final CountDownLatch errorOrDisconnect = new CountDownLatch(1);

        DualChannelStompClient(UUID userId, int tokenVersion) throws Exception {
            this.userId = userId;
            this.client = new WebSocketStompClient(new StandardWebSocketClient());
            this.client.setMessageConverter(new MappingJackson2MessageConverter());
            StompHeaders connectHeaders = new StompHeaders();
            connectHeaders.add("Authorization", bearer(userId, tokenVersion));
            WebSocketHttpHeaders handshake = new WebSocketHttpHeaders();
            handshake.setOrigin("http://localhost:5173");
            this.session = client.connectAsync(
                    URI.create("ws://localhost:" + port + "/ws"),
                    handshake,
                    connectHeaders,
                    new StompSessionHandlerAdapter() {
                        @Override
                        public void handleException(
                                StompSession s, StompCommand command, StompHeaders headers,
                                byte[] payload, Throwable exception) {
                            errorOrDisconnect.countDown();
                        }

                        @Override
                        public void handleTransportError(StompSession s, Throwable exception) {
                            errorOrDisconnect.countDown();
                        }
                    }).get(5, TimeUnit.SECONDS);
        }

        public void subscribeBoard(UUID boardId) throws InterruptedException {
            String destination = "/topic/boards/" + boardId;
            session.subscribe(destination, mapHandler(boardFrames));
            awaitCondition(Duration.ofSeconds(5), () -> hasSubscription(destination::equals),
                    "Timed out waiting for STOMP board subscription " + destination);
        }

        public void subscribeNotifications() throws InterruptedException {
            session.subscribe("/user/queue/notifications", mapHandler(notificationFrames));
            awaitCondition(Duration.ofSeconds(5),
                    () -> hasSubscription(dest -> "/user/queue/notifications".equals(dest)
                            || dest.contains("/queue/notifications-user")),
                    "Timed out waiting for STOMP user notification subscription for " + userId);
        }

        public void subscribeRawExpectingRejection(String destination) {
            session.subscribe(destination, mapHandler(notificationFrames));
        }

        public boolean awaitErrorOrDisconnect(Duration timeout) throws InterruptedException {
            return errorOrDisconnect.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        public Map<?, ?> pollBoardFrame(Duration timeout) throws InterruptedException {
            return boardFrames.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        public Map<?, ?> pollNotificationFrame(Duration timeout) throws InterruptedException {
            return notificationFrames.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        public BlockingQueue<Map<?, ?>> boardFrames() {
            return boardFrames;
        }

        public BlockingQueue<Map<?, ?>> notificationFrames() {
            return notificationFrames;
        }

        private boolean hasSubscription(java.util.function.Predicate<String> predicate) {
            var user = simpUserRegistry.getUser(userId.toString());
            return user != null && user.getSessions().stream()
                    .flatMap(s -> s.getSubscriptions().stream())
                    .anyMatch(sub -> predicate.test(sub.getDestination()));
        }

        private static StompFrameHandler mapHandler(BlockingQueue<Map<?, ?>> sink) {
            return new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return Map.class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    if (payload instanceof Map<?, ?> map) {
                        sink.add(map);
                    }
                }
            };
        }

        @Override
        public void close() {
            try {
                if (session != null && session.isConnected()) {
                    session.disconnect();
                }
            } catch (RuntimeException ignored) {
                // Best-effort cleanup during test teardown.
            } finally {
                client.stop();
            }
        }
    }

    protected record TenantFixture(
            UUID wsA,
            UUID wsB,
            UUID ownerA,
            UUID assigneeA,
            UUID assigneeA2,
            UUID collabA,
            UUID revokedA,
            UUID ownerB,
            UUID assigneeB,
            UUID boardA,
            UUID boardArchived,
            UUID boardB,
            Map<String, UUID> colsA,
            Map<String, UUID> colsArchived,
            Map<String, UUID> colsB,
            UUID taskA1,
            UUID taskA2,
            UUID taskUnassigned,
            UUID taskRevoked,
            UUID taskArchived,
            UUID taskB1) {
    }
}
