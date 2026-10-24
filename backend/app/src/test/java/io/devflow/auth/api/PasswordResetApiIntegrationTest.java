package io.devflow.auth.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.devflow.auth.internal.passwordreset.PasswordChangedEmailRequestedEvent;
import io.devflow.auth.internal.passwordreset.PasswordResetEmailRequestedEvent;
import io.devflow.auth.internal.security.JwtTokenProvider;
import io.devflow.auth.internal.security.RateLimiterService;
import io.devflow.auth.internal.security.StompSessionRegistry;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * T-008: forgot password and password reset through the public REST API, on PostgreSQL 17 with the Flyway schema.
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
@ExtendWith(OutputCaptureExtension.class)
@Testcontainers
class PasswordResetApiIntegrationTest {

    private static final String OLD_PASSWORD = "OldPassw0rd!";
    private static final String NEW_PASSWORD = "NewPassw0rd!";
    private static final String FORGOT_MESSAGE =
            "If an account exists for this email, a password reset link has been sent.";
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
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private StompSessionRegistry stompSessionRegistry;

    @Autowired
    private ApplicationEvents applicationEvents;

    @MockitoBean
    private JavaMailSender mailSender;

    @BeforeEach
    void setUp() {
        rateLimiterService.clearAll();
        when(mailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage((Session) null));
    }

    // --- Forgot password ---

    @Test
    void forgotPasswordAnswersIdenticallyAndQuicklyWhetherOrNotAnAccountExists() throws Exception {
        // A slow mail provider must not show up in the response time of the account that gets an email.
        doAnswer(inv -> {
            Thread.sleep(1500);
            return null;
        }).when(mailSender).send(any(MimeMessage.class));
        TestUser active = user("active", "ACTIVE");
        TestUser pending = user("pending", "PENDING_VERIFICATION");

        List<String> bodies = new ArrayList<>();
        for (String email : List.of(active.email(), "nobody-" + UUID.randomUUID() + "@example.test",
                pending.email())) {
            long started = System.nanoTime();
            String body = forgot(email).andExpect(status().isAccepted())
                    .andReturn().getResponse().getContentAsString();
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertThat(elapsedMs).isLessThan(1000);
            bodies.add(body);
        }

        assertThat(bodies).containsOnly(bodies.get(0));
        assertThat(objectMapper.readTree(bodies.get(0)).get("message").asText()).isEqualTo(FORGOT_MESSAGE);
        assertThat(applicationEvents.stream(PasswordResetEmailRequestedEvent.class))
                .singleElement()
                .satisfies(event -> assertThat(event.userId()).isEqualTo(active.id()));
        verify(mailSender, timeout(5000)).send(any(MimeMessage.class));
    }

    @Test
    void forgotPasswordValidatesAndRateLimitsPerEmailAndPerIp() throws Exception {
        forgot("not-an-email").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.email").exists());

        String email = "flood-" + UUID.randomUUID() + "@example.test";
        for (int i = 0; i < 3; i++) {
            forgot(email).andExpect(status().isAccepted());
        }
        forgot(email).andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"));

        String ip = "203.0.113.77";
        for (int i = 0; i < 5; i++) {
            forgotFrom(ip, "ip-" + i + "-" + UUID.randomUUID() + "@example.test").andExpect(status().isAccepted());
        }
        forgotFrom(ip, "ip-last@example.test").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"));
    }

    // --- Reset password ---

    @Test
    void resetSignsTheUserOutEverywhereAndSendsANotice(CapturedOutput output) throws Exception {
        TestUser user = user("resetter", "ACTIVE");
        JsonNode session = readJson(login(user.email(), OLD_PASSWORD).andExpect(status().isOk()));
        String oldAccess = session.get("accessToken").asText();
        String oldRefresh = session.get("refreshToken").asText();
        mockMvc.perform(post("/api/v1/auth/tokens").header(HttpHeaders.AUTHORIZATION, "Bearer " + oldAccess)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("name", "Cursor"))))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + oldAccess))
                .andExpect(status().isOk());
        WebSocketSession socket = openStompSocket(user.id());
        // No pause: the old session is usually from the same second as the reset, which must not matter.

        forgot(user.email()).andExpect(status().isAccepted());
        String token = lastResetToken();
        reset(token, NEW_PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").exists());
        verify(socket).close(argThat(closeStatus -> closeStatus.getCode() == CloseStatus.POLICY_VIOLATION.getCode()));

        login(user.email(), OLD_PASSWORD).andExpect(status().isUnauthorized());
        JsonNode fresh = readJson(login(user.email(), NEW_PASSWORD).andExpect(status().isOk()));
        mockMvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("refreshToken", oldRefresh))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + oldAccess))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + fresh.get("accessToken").asText()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("refreshToken", fresh.get("refreshToken").asText()))))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM personal_access_tokens "
                + "WHERE user_id = ? AND revoked_at IS NULL", Long.class, user.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT password_changed_at IS NOT NULL FROM users WHERE id = ?",
                Boolean.class, user.id())).isTrue();
        assertThat(jdbc.queryForObject("SELECT token_version FROM users WHERE id = ?", Integer.class, user.id()))
                .isEqualTo(1);
        assertThat(applicationEvents.stream(PasswordChangedEmailRequestedEvent.class))
                .singleElement()
                .satisfies(event -> assertThat(event.userId()).isEqualTo(user.id()));
        verify(mailSender, timeout(5000).times(2)).send(any(MimeMessage.class));
        assertThat(output).doesNotContain(token).doesNotContain(user.email());
    }

    @Test
    void tokensWithThePreResetVersionAreRejectedWhateverTheirIssueTime() throws Exception {
        TestUser user = user("same-second", "ACTIVE");
        forgot(user.email()).andExpect(status().isAccepted());
        reset(lastResetToken(), NEW_PASSWORD).andExpect(status().isOk());

        // Minted after the reset, so their iat is not before it (the old time watermark accepted these), but they
        // carry the pre-reset version 0, exactly like a session issued in the same second as the reset.
        String staleAccess = jwtTokenProvider.generateAccessToken(user.id(), user.email(), 0);
        String staleRefresh = jwtTokenProvider.generateRefreshToken(user.id(), user.email(), 0);

        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + staleAccess))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("refreshToken", staleRefresh))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + jwtTokenProvider.generateAccessToken(user.id(), user.email(), 1)))
                .andExpect(status().isOk());
    }

    @Test
    void concurrentForgotPasswordRequestsLeaveExactlyOneOpenLink() throws Exception {
        TestUser user = user("parallel", "ACTIVE");

        // Three requests stay within the per-email limit; each comes from its own IP.
        List<Integer> statuses = concurrently(List.of(
                () -> statusOf(forgot(user.email())),
                () -> statusOf(forgot(user.email())),
                () -> statusOf(forgot(user.email()))));

        // All succeed (no unique-index violation surfacing as a 500) and every link but one is invalidated.
        assertThat(statuses).containsOnly(202);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM verification_tokens "
                + "WHERE user_id = ? AND type = 'PASSWORD_RESET'", Long.class, user.id())).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM verification_tokens "
                + "WHERE user_id = ? AND type = 'PASSWORD_RESET' AND used_at IS NULL", Long.class, user.id()))
                .isEqualTo(1);
    }

    @Test
    void passwordOverSeventyTwoUtf8BytesIsA400NotA500() throws Exception {
        TestUser user = user("bytes", "ACTIVE");
        forgot(user.email()).andExpect(status().isAccepted());
        String token = lastResetToken();
        // 38 characters, within @Size, but 73 bytes in UTF-8: BCrypt would throw.
        String tooLong = "Aa1" + "é".repeat(35);

        reset(token, tooLong).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.newPassword").exists());
        login(user.email(), tooLong).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        // Rejected before the token was touched; a 72-byte accented password is accepted.
        reset(token, "Aa1" + "é".repeat(34) + "x").andExpect(status().isOk());
        login(user.email(), "Aa1" + "é".repeat(34) + "x").andExpect(status().isOk());
    }

    @Test
    void resetTokenIsSingleUseAndSupersededByANewerOne() throws Exception {
        TestUser user = user("twice", "ACTIVE");
        forgot(user.email()).andExpect(status().isAccepted());
        String first = lastResetToken();
        forgot(user.email()).andExpect(status().isAccepted());
        String second = lastResetToken();

        reset(first, NEW_PASSWORD).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESET_TOKEN_INVALID"));
        reset(second, NEW_PASSWORD).andExpect(status().isOk());
        reset(second, "Another1Pass").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESET_TOKEN_INVALID"));
        assertThat(jdbc.queryForObject("SELECT token_hash FROM verification_tokens WHERE type = 'PASSWORD_RESET' "
                + "AND user_id = ? ORDER BY created_at DESC LIMIT 1", String.class, user.id()))
                .matches("[0-9a-f]{64}").isNotEqualTo(second);
    }

    @Test
    void expiredUnknownAndVerificationTokensAreRejected() throws Exception {
        TestUser user = user("late", "ACTIVE");
        forgot(user.email()).andExpect(status().isAccepted());
        String token = lastResetToken();
        jdbc.update("UPDATE verification_tokens SET expires_at = now() - interval '1 second' "
                + "WHERE user_id = ? AND type = 'PASSWORD_RESET'", user.id());

        reset(token, NEW_PASSWORD).andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("RESET_TOKEN_EXPIRED"));
        reset("not-a-real-token", NEW_PASSWORD).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESET_TOKEN_INVALID"));

        // An email verification token cannot be used to reset a password.
        jdbc.update("INSERT INTO verification_tokens(user_id, token_hash, type, expires_at) "
                + "VALUES (?, encode(sha256(convert_to('verify-token', 'UTF8')), 'hex'), 'EMAIL_VERIFICATION', "
                + "now() + interval '1 hour')", user.id());
        reset("verify-token", NEW_PASSWORD).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESET_TOKEN_INVALID"));
        login(user.email(), OLD_PASSWORD).andExpect(status().isOk());
    }

    @Test
    void weakPasswordIsRejectedWithoutConsumingTheToken() throws Exception {
        TestUser user = user("weak", "ACTIVE");
        forgot(user.email()).andExpect(status().isAccepted());
        String token = lastResetToken();

        for (String weak : List.of("short1A", "alllowercase1", "ALLUPPERCASE1", "NoDigitsHere", "x".repeat(73))) {
            reset(token, weak).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors.newPassword").exists());
        }
        reset(token, NEW_PASSWORD).andExpect(status().isOk());
    }

    @Test
    void resetIsRateLimitedPerIp() throws Exception {
        String ip = "203.0.113.88";
        for (int i = 0; i < 10; i++) {
            resetFrom(ip, "guess-" + i, NEW_PASSWORD).andExpect(status().isBadRequest());
        }
        resetFrom(ip, "guess-final", NEW_PASSWORD).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"));
    }

    @Test
    void concurrentResetsWithOneTokenSucceedExactlyOnce() throws Exception {
        TestUser user = user("race", "ACTIVE");
        forgot(user.email()).andExpect(status().isAccepted());
        String token = lastResetToken();

        List<Integer> statuses = concurrently(List.of(
                () -> statusOf(reset(token, "RaceWinner1A")),
                () -> statusOf(reset(token, "RaceWinner2B"))));

        assertThat(statuses).containsExactlyInAnyOrder(200, 400);
        String winner = statuses.get(0) == 200 ? "RaceWinner1A" : "RaceWinner2B";
        String loser = statuses.get(0) == 200 ? "RaceWinner2B" : "RaceWinner1A";
        login(user.email(), winner).andExpect(status().isOk());
        login(user.email(), loser).andExpect(status().isUnauthorized());
        assertThat(applicationEvents.stream(PasswordChangedEmailRequestedEvent.class)).hasSizeLessThanOrEqualTo(1);
    }

    // --- Helpers ---

    private record TestUser(UUID id, String email) {}

    private TestUser user(String label, String status) {
        UUID id = UUID.randomUUID();
        String email = label + "-" + id + "@devflow.test";
        jdbc.update("INSERT INTO users(id, email, password_hash, full_name, status) VALUES (?, ?, ?, ?, ?)",
                id, email, passwordEncoder.encode(OLD_PASSWORD), "T-008 " + label, status);
        return new TestUser(id, email);
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

    private String lastResetToken() {
        return applicationEvents.stream(PasswordResetEmailRequestedEvent.class)
                .reduce((first, second) -> second)
                .orElseThrow()
                .rawToken();
    }

    private ResultActions forgot(String email) throws Exception {
        return forgotFrom(nextIp(), email);
    }

    private ResultActions forgotFrom(String ip, String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/forgot-password")
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("email", email))));
    }

    private ResultActions reset(String token, String newPassword) throws Exception {
        return resetFrom(nextIp(), token, newPassword);
    }

    private ResultActions resetFrom(String ip, String token, String newPassword) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/reset-password")
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("token", token, "newPassword", newPassword))));
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

    private static String nextIp() {
        int n = IP_SUFFIX.getAndIncrement();
        return "198.51." + (n / 250) + "." + (n % 250 + 1);
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
