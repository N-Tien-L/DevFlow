package io.devflow.auth.internal.antiabuse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.devflow.auth.api.RegisterRequest;
import io.devflow.auth.internal.TestAuthApplication;
import io.devflow.auth.internal.repository.UserRepository;
import io.devflow.auth.internal.repository.VerificationTokenRepository;
import io.devflow.auth.internal.security.RateLimiterService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * End-to-end check of Turnstile inside the Spring context: the Resilience4j aspect, its fallback and
 * the exception handler, against a local stub of the Siteverify API.
 */
@SpringBootTest(classes = TestAuthApplication.class, properties = {
        "devflow.turnstile.enabled=true",
        "devflow.turnstile.secret-key=test-secret",
        "devflow.turnstile.read-timeout=500ms",
        "resilience4j.circuitbreaker.instances.turnstile.sliding-window-size=2",
        "resilience4j.circuitbreaker.instances.turnstile.minimum-number-of-calls=2",
        "resilience4j.circuitbreaker.instances.turnstile.failure-rate-threshold=50",
        "resilience4j.circuitbreaker.instances.turnstile.wait-duration-in-open-state=60s",
        "resilience4j.circuitbreaker.instances.turnstile.ignore-exceptions[0]="
                + "io.devflow.auth.internal.exception.BotChallengeFailedException"
})
@AutoConfigureMockMvc
@DisplayName("Turnstile registration integration (Spring context + Siteverify stub)")
class TurnstileRegistrationIntegrationTest {

    private static final String STRONG_PASSWORD = "StrongP4ssword!";
    private static final AtomicReference<Integer> STUB_STATUS = new AtomicReference<>(200);
    private static final AtomicReference<String> STUB_BODY = new AtomicReference<>("{\"success\":true}");
    private static final AtomicInteger STUB_DELAY_MS = new AtomicInteger();
    private static final AtomicInteger STUB_CALLS = new AtomicInteger();
    private static final HttpServer SITEVERIFY = startStub();
    private static final AtomicInteger IP_SUFFIX = new AtomicInteger(1);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private VerificationTokenRepository tokenRepository;

    @Autowired
    private RateLimiterService rateLimiterService;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @MockitoBean
    private JavaMailSender mailSender;

    @DynamicPropertySource
    static void siteverifyUrl(DynamicPropertyRegistry registry) {
        registry.add("devflow.turnstile.siteverify-url",
                () -> "http://127.0.0.1:" + SITEVERIFY.getAddress().getPort() + "/siteverify");
    }

    @AfterAll
    static void stopStub() {
        SITEVERIFY.stop(0);
    }

    @BeforeEach
    void reset() {
        tokenRepository.deleteAll();
        userRepository.deleteAll();
        rateLimiterService.clearAll();
        circuitBreakerRegistry.circuitBreaker(TurnstileService.RESILIENCE_NAME).reset();
        STUB_STATUS.set(200);
        STUB_BODY.set("{\"success\":true}");
        STUB_DELAY_MS.set(0);
        STUB_CALLS.set(0);
        Mockito.when(mailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage((Session) null));
    }

    private ResultActions register(String email, String token) throws Exception {
        String ip = "198.51.100." + IP_SUFFIX.getAndIncrement();
        return mockMvc.perform(post("/api/v1/auth/register")
                .with(req -> {
                    req.setRemoteAddr(ip);
                    return req;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new RegisterRequest(email, STRONG_PASSWORD, "Alex", token))));
    }

    @Test
    @DisplayName("A token Siteverify accepts lets the registration through")
    void acceptedToken_registers() throws Exception {
        register("ok@devflow.io", "valid-token").andExpect(status().isCreated());

        assertThat(STUB_CALLS.get()).isEqualTo(1);
        assertThat(userRepository.findByEmail("ok@devflow.io")).isPresent();
    }

    @Test
    @DisplayName("Missing token is rejected with 400 BOT_CHALLENGE_FAILED without calling Siteverify")
    void missingToken_returns400() throws Exception {
        register("missing@devflow.io", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("BOT_CHALLENGE_FAILED")));

        assertThat(STUB_CALLS.get()).isZero();
        assertThat(userRepository.findByEmail("missing@devflow.io")).isEmpty();
    }

    @Test
    @DisplayName("Rejected tokens return 400 and never open the circuit")
    void rejectedToken_returns400_andCircuitStaysClosed() throws Exception {
        STUB_BODY.set("{\"success\":false,\"error-codes\":[\"invalid-input-response\"]}");

        for (int i = 0; i < 4; i++) {
            register("bot" + i + "@devflow.io", "forged")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code", is("BOT_CHALLENGE_FAILED")));
        }

        assertThat(circuitState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(userRepository.count()).isZero();
    }

    @Test
    @DisplayName("Siteverify 5xx returns 503 via the fallback, then the open circuit stops calling Cloudflare")
    void providerOutage_returns503_andOpensCircuit() throws Exception {
        STUB_STATUS.set(500);
        STUB_BODY.set("{}");

        for (int i = 0; i < 2; i++) {
            register("outage" + i + "@devflow.io", "token")
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code", is("BOT_PROTECTION_UNAVAILABLE")))
                    .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
        }
        assertThat(circuitState()).isEqualTo(CircuitBreaker.State.OPEN);
        int callsBeforeOpen = STUB_CALLS.get();

        register("outage-open@devflow.io", "token")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code", is("BOT_PROTECTION_UNAVAILABLE")));

        assertThat(STUB_CALLS.get()).isEqualTo(callsBeforeOpen);
        assertThat(userRepository.count()).isZero();
    }

    @Test
    @DisplayName("A Siteverify response slower than the read timeout fails closed with 503")
    void slowProvider_returns503() throws Exception {
        STUB_DELAY_MS.set(1_500);

        register("slow@devflow.io", "token")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code", is("BOT_PROTECTION_UNAVAILABLE")));

        assertThat(userRepository.findByEmail("slow@devflow.io")).isEmpty();
    }

    private CircuitBreaker.State circuitState() {
        return circuitBreakerRegistry.circuitBreaker(TurnstileService.RESILIENCE_NAME).getState();
    }

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/siteverify", exchange -> {
                STUB_CALLS.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                try {
                    Thread.sleep(STUB_DELAY_MS.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                byte[] body = STUB_BODY.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                try {
                    exchange.sendResponseHeaders(STUB_STATUS.get(), body.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                } catch (IOException ignored) {
                    // The client already gave up (read-timeout test).
                }
            });
            server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException("Could not start Siteverify stub", e);
        }
    }
}
