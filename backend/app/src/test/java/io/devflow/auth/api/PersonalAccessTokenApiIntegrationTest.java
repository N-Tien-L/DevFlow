package io.devflow.auth.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.devflow.auth.internal.security.JwtTokenProvider;
import io.devflow.auth.internal.security.RateLimiterService;
import io.devflow.auth.internal.verification.VerificationCleanupJob;
import io.devflow.common.security.AuthenticatedActor;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * T-038: personal access tokens for MCP clients, through the REST API and the MCP paths, on PostgreSQL 17 with
 * the Flyway schema. {@link McpProbeController} is a test-only endpoint under the MCP prefix that echoes the
 * authenticated user, standing in for the MCP tools (T-035).
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none",
        "spring.ai.model.image=none",
        "spring.ai.model.moderation=none",
        "spring.ai.model.audio.speech=none",
        "spring.ai.model.audio.transcription=none",
        "devflow.pat.max-active-per-user=3",
        "devflow.rate-limit.policies.pat-use.capacity=8",
        "devflow.rate-limit.policies.pat-use.period=1m"
})
@AutoConfigureMockMvc
@Import(PersonalAccessTokenApiIntegrationTest.McpProbeController.class)
@ExtendWith(OutputCaptureExtension.class)
@Testcontainers
class PersonalAccessTokenApiIntegrationTest {

    private static final String TOKEN_PATTERN = "dfp_[0-9a-f]{12}[A-Za-z0-9_-]{43}";
    private static final String PROBE = "/mcp/test/whoami";

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
     * Test-only endpoint under the MCP path prefix.
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
    private JwtTokenProvider tokenProvider;

    @Autowired
    private RateLimiterService rateLimiterService;

    @Autowired
    private VerificationCleanupJob cleanupJob;

    private TestUser user;

    @BeforeEach
    void setUp() {
        // Failed-attempt budgets are per client IP and every MockMvc request comes from 127.0.0.1.
        rateLimiterService.clearAll();
        user = activeUser("pat-owner");
    }

    // --- Management API ---

    @Test
    void createShowsTheTokenOnceAndStoresOnlyItsHash(CapturedOutput output) throws Exception {
        JsonNode created = readJson(createToken(user, Map.of("name", "  Cursor <b>laptop</b> ", "expiresInDays", 30))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.name").value("Cursor laptop"))
                .andExpect(jsonPath("$.token").value(matchesPattern(TOKEN_PATTERN)))
                .andExpect(jsonPath("$.expiresAt").exists()));
        String raw = created.get("token").asText();
        UUID id = UUID.fromString(created.get("id").asText());
        assertThat(created.get("tokenPrefix").asText()).isEqualTo(raw.substring(0, 16));

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM personal_access_tokens WHERE id = ?", id);
        assertThat(row.get("token_hash")).isEqualTo(sha256(raw));
        assertThat(row.values()).noneMatch(value -> value != null && value.toString().contains(raw));

        String listing = mockMvc.perform(get("/api/v1/auth/tokens").header(HttpHeaders.AUTHORIZATION, jwt(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(id.toString()))
                .andExpect(jsonPath("$[0].tokenPrefix").value(raw.substring(0, 16)))
                .andExpect(jsonPath("$[0].expired").value(false))
                .andExpect(jsonPath("$[0].token").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(listing).doesNotContain(raw);

        mcpProbe(raw).andExpect(status().isOk());
        assertThat(output).doesNotContain(raw);
    }

    @Test
    void createValidatesInputWithProblemDetails() throws Exception {
        createToken(user, Map.of("name", " ")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").exists());
        createToken(user, Map.of("name", "x".repeat(101))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").exists());
        createToken(user, Map.of("name", "<b></b>")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").exists());
        createToken(user, Map.of("name", "ok", "expiresInDays", 0)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.expiresInDays").exists());
        createToken(user, Map.of("name", "ok", "expiresInDays", 366)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.expiresInDays").exists());
        mockMvc.perform(post("/api/v1/auth/tokens").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "anonymous"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void revokeStopsTheTokenAndIsScopedToItsOwner() throws Exception {
        TestUser other = activeUser("other");
        Map<String, String> token = newToken(user, "Claude Code");
        UUID id = UUID.fromString(token.get("id"));

        // Another user can neither see nor revoke it (IDOR); the token keeps working.
        mockMvc.perform(get("/api/v1/auth/tokens").header(HttpHeaders.AUTHORIZATION, jwt(other)))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(delete("/api/v1/auth/tokens/{id}", id).header(HttpHeaders.AUTHORIZATION, jwt(other)))
                .andExpect(status().isNotFound());
        mcpProbe(token.get("token")).andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/auth/tokens/{id}", id).header(HttpHeaders.AUTHORIZATION, jwt(user)))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/auth/tokens/{id}", id).header(HttpHeaders.AUTHORIZATION, jwt(user)))
                .andExpect(status().isNotFound());
        mcpProbe(token.get("token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_ACCESS_TOKEN"))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\""));
        mockMvc.perform(get("/api/v1/auth/tokens").header(HttpHeaders.AUTHORIZATION, jwt(user)))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void quotaCountsOnlyLiveTokens() throws Exception {
        for (int i = 0; i < 3; i++) {
            newToken(user, "token " + i);
        }
        createToken(user, Map.of("name", "one too many"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TOKEN_LIMIT_REACHED"));

        UUID first = jdbc.queryForObject(
                "SELECT id FROM personal_access_tokens WHERE user_id = ? ORDER BY created_at LIMIT 1", UUID.class,
                user.id());
        mockMvc.perform(delete("/api/v1/auth/tokens/{id}", first).header(HttpHeaders.AUTHORIZATION, jwt(user)))
                .andExpect(status().isNoContent());
        createToken(user, Map.of("name", "replacement")).andExpect(status().isCreated());
    }

    // --- MCP authentication ---

    @Test
    void validTokenAuthenticatesTheOwnerOnMcpPathsAndRecordsUsage() throws Exception {
        Map<String, String> token = newToken(user, "Cursor");

        mcpProbe(token.get("token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(user.id().toString()));

        assertThat(jdbc.queryForObject("SELECT last_used_at FROM personal_access_tokens WHERE id = ?::uuid",
                Timestamp.class, token.get("id"))).isNotNull();
    }

    @Test
    void expiredUnknownAndMalformedTokensAreRejectedAlike() throws Exception {
        Map<String, String> token = newToken(user, "Short lived");
        jdbc.update("UPDATE personal_access_tokens SET created_at = now() - interval '2 days', "
                + "expires_at = now() - interval '1 second' WHERE id = ?::uuid", token.get("id"));
        String unknown = token.get("token").substring(0, 4) + "000000000000" + token.get("token").substring(16);

        for (String presented : List.of(token.get("token"), unknown, "dfp_not-a-real-token")) {
            mcpProbe(presented)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_ACCESS_TOKEN"))
                    .andExpect(jsonPath("$.detail").value("Invalid, expired or revoked access token"));
        }
        mockMvc.perform(get(PROBE)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/auth/tokens").header(HttpHeaders.AUTHORIZATION, jwt(user)))
                .andExpect(jsonPath("$[0].expired").value(true));
    }

    @Test
    void tokenIsNotAcceptedOutsideMcpPathsAndJwtIsUnaffected() throws Exception {
        Map<String, String> token = newToken(user, "Scoped");
        String patBearer = "Bearer " + token.get("token");

        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, patBearer))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/auth/tokens").header(HttpHeaders.AUTHORIZATION, patBearer))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/auth/workspaces").header(HttpHeaders.AUTHORIZATION, patBearer))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, jwt(user)))
                .andExpect(status().isOk());
        mockMvc.perform(get(PROBE).header(HttpHeaders.AUTHORIZATION, jwt(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(user.id().toString()));
    }

    @Test
    void realMcpMessageEndpointRequiresAuthentication() throws Exception {
        Map<String, String> token = newToken(user, "Transport");

        mockMvc.perform(post("/mcp/message").param("sessionId", "unknown")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        int authenticated = mockMvc.perform(post("/mcp/message").param("sessionId", "unknown")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token.get("token"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn().getResponse().getStatus();
        assertThat(authenticated).isNotIn(401, 403);

        mockMvc.perform(get("/sse")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/sse").header(HttpHeaders.AUTHORIZATION, "Bearer " + token.get("token")))
                .andExpect(request().asyncStarted());
    }

    @Test
    void lastUsedIsWrittenAtMostOncePerInterval() throws Exception {
        Map<String, String> token = newToken(user, "Busy agent");
        Timestamp recent = Timestamp.from(Instant.now().minusSeconds(60));
        jdbc.update("UPDATE personal_access_tokens SET last_used_at = ? WHERE id = ?::uuid", recent, token.get("id"));
        Timestamp stored = lastUsed(token.get("id"));

        mcpProbe(token.get("token")).andExpect(status().isOk());
        assertThat(lastUsed(token.get("id"))).isEqualTo(stored);

        Timestamp stale = Timestamp.from(Instant.now().minusSeconds(600));
        jdbc.update("UPDATE personal_access_tokens SET last_used_at = ? WHERE id = ?::uuid", stale, token.get("id"));
        mcpProbe(token.get("token")).andExpect(status().isOk());
        assertThat(lastUsed(token.get("id"))).isAfter(stale);
    }

    @Test
    void usageIsRateLimitedPerTokenAndFailuresPerClient() throws Exception {
        Map<String, String> busy = newToken(user, "Busy");
        Map<String, String> calm = newToken(user, "Calm");
        for (int i = 0; i < 8; i++) {
            mcpProbe(busy.get("token")).andExpect(status().isOk());
        }
        mcpProbe(busy.get("token"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
        mcpProbe(calm.get("token")).andExpect(status().isOk());

        String unknown = busy.get("token").substring(0, 4) + "000000000000" + busy.get("token").substring(16);
        for (int i = 0; i < 20; i++) {
            mcpProbe(unknown).andExpect(status().isUnauthorized());
        }
        mcpProbe(unknown).andExpect(status().isTooManyRequests());
    }

    // --- Concurrency ---

    @Test
    void concurrentCreatesCannotOvershootTheQuota() throws Exception {
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String name = "race " + i;
            calls.add(() -> statusOf(createToken(user, Map.of("name", name))));
        }

        List<Integer> statuses = concurrently(calls);

        assertThat(statuses).containsOnly(201, 409);
        assertThat(statuses).filteredOn(s -> s == 201).hasSize(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM personal_access_tokens WHERE user_id = ?", Long.class,
                user.id())).isEqualTo(3);
    }

    @Test
    void concurrentRevokesSucceedExactlyOnce() throws Exception {
        UUID id = UUID.fromString(newToken(user, "Double revoke").get("id"));

        List<Integer> statuses = concurrently(List.of(
                () -> statusOf(mockMvc.perform(delete("/api/v1/auth/tokens/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, jwt(user)))),
                () -> statusOf(mockMvc.perform(delete("/api/v1/auth/tokens/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, jwt(user))))));

        assertThat(statuses).containsExactlyInAnyOrder(204, 404);
    }

    @Test
    void revokeRacingUseNeverLeavesTheTokenUsable() throws Exception {
        for (int round = 0; round < 5; round++) {
            Map<String, String> token = newToken(user, "Contested " + round);

            List<Integer> statuses = concurrently(List.of(
                    () -> statusOf(mcpProbe(token.get("token"))),
                    () -> statusOf(mockMvc.perform(delete("/api/v1/auth/tokens/{id}", token.get("id"))
                            .header(HttpHeaders.AUTHORIZATION, jwt(user))))));

            assertThat(statuses.get(0)).isIn(200, 401);
            assertThat(statuses.get(1)).isEqualTo(204);
            mcpProbe(token.get("token")).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void cleanupJobPurgesOnlyLongRevokedOrExpiredTokens() throws Exception {
        Map<String, String> live = newToken(user, "Live");
        Map<String, String> old = newToken(user, "Old");
        jdbc.update("UPDATE personal_access_tokens SET revoked_at = now() - interval '31 days' WHERE id = ?::uuid",
                old.get("id"));

        cleanupJob.purge();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM personal_access_tokens WHERE id = ?::uuid", Long.class,
                old.get("id"))).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM personal_access_tokens WHERE id = ?::uuid", Long.class,
                live.get("id"))).isEqualTo(1);
    }

    // --- Helpers ---

    private record TestUser(UUID id, String email) {}

    private TestUser activeUser(String label) {
        UUID id = UUID.randomUUID();
        String email = label + "-" + id + "@devflow.test";
        jdbc.update("INSERT INTO users(id, email, password_hash, full_name, status, email_verified_at) "
                + "VALUES (?, ?, 'test-hash', ?, 'ACTIVE', now())", id, email, "T-038 " + label);
        return new TestUser(id, email);
    }

    private Map<String, String> newToken(TestUser owner, String name) throws Exception {
        JsonNode body = readJson(createToken(owner, Map.of("name", name)).andExpect(status().isCreated()));
        Map<String, String> token = new HashMap<>();
        token.put("id", body.get("id").asText());
        token.put("token", body.get("token").asText());
        return token;
    }

    private ResultActions createToken(TestUser owner, Object body) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/tokens")
                .header(HttpHeaders.AUTHORIZATION, jwt(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(body)));
    }

    private ResultActions mcpProbe(String rawToken) throws Exception {
        return mockMvc.perform(get(PROBE).header(HttpHeaders.AUTHORIZATION, "Bearer " + rawToken));
    }

    private Timestamp lastUsed(String tokenId) {
        return jdbc.queryForObject("SELECT last_used_at FROM personal_access_tokens WHERE id = ?::uuid",
                Timestamp.class, tokenId);
    }

    private String sha256(String raw) {
        return jdbc.queryForObject("SELECT encode(sha256(convert_to(?, 'UTF8')), 'hex')", String.class, raw);
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

    private String jwt(TestUser owner) {
        return "Bearer " + tokenProvider.generateAccessToken(owner.id(), owner.email());
    }

    private JsonNode readJson(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}
