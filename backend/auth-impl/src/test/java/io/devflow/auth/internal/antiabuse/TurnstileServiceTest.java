package io.devflow.auth.internal.antiabuse;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.devflow.auth.internal.exception.BotChallengeFailedException;
import io.devflow.auth.internal.support.AuthMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

/**
 * Exercises the Siteverify exchange directly. The circuit breaker and its fallback are wired by
 * Resilience4j's Spring aspect and are not active on this plain instance.
 */
@DisplayName("TurnstileService Unit Tests")
class TurnstileServiceTest {

    private static final String SITEVERIFY = "https://turnstile.test/siteverify";

    private TurnstileProperties properties;
    private AuthMetrics metrics;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        properties = new TurnstileProperties();
        properties.setEnabled(true);
        properties.setSecretKey("test-secret");
        properties.setSiteverifyUrl(SITEVERIFY);
        metrics = mock(AuthMetrics.class);
    }

    private TurnstileService service() {
        return new TurnstileService(properties, metrics, RestClient.builder());
    }

    private TurnstileService serviceWithMockServer() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        return new TurnstileService(properties, metrics, builder.build());
    }

    @Test
    @DisplayName("Posts secret, token and client IP as a form and accepts success=true")
    void acceptsValidToken() {
        TurnstileService service = serviceWithMockServer();
        server.expect(requestTo(SITEVERIFY))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formDataContains(java.util.Map.of(
                        "secret", "test-secret", "response", "token-1", "remoteip", "203.0.113.10")))
                .andRespond(withSuccess("{\"success\":true}", MediaType.APPLICATION_JSON));

        assertThatCode(() -> service.verify("token-1", "203.0.113.10")).doesNotThrowAnyException();

        server.verify();
        verify(metrics).antiAbuse("turnstile", "passed");
    }

    @Test
    @DisplayName("Rejects a token Cloudflare reports as invalid or already used")
    void rejectsInvalidToken() {
        TurnstileService service = serviceWithMockServer();
        server.expect(requestTo(SITEVERIFY)).andRespond(withSuccess(
                "{\"success\":false,\"error-codes\":[\"timeout-or-duplicate\"]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> service.verify("replayed", "203.0.113.10"))
                .isInstanceOf(BotChallengeFailedException.class);
        verify(metrics).antiAbuse("turnstile", "rejected");
    }

    @Test
    @DisplayName("Rejects a missing or oversized token without calling Cloudflare")
    void rejectsMissingToken() {
        TurnstileService service = serviceWithMockServer();

        assertThatThrownBy(() -> service.verify(" ", "203.0.113.10"))
                .isInstanceOf(BotChallengeFailedException.class);
        assertThatThrownBy(() -> service.verify("x".repeat(TurnstileService.MAX_TOKEN_LENGTH + 1), null))
                .isInstanceOf(BotChallengeFailedException.class);

        server.verify();
    }

    @Test
    @DisplayName("Propagates provider errors so the circuit breaker can count them")
    void providerErrorPropagates() {
        TurnstileService service = serviceWithMockServer();
        server.expect(requestTo(SITEVERIFY)).andRespond(withServerError());

        assertThatThrownBy(() -> service.verify("token", null)).isInstanceOf(HttpServerErrorException.class);
    }

    @Test
    @DisplayName("Fallback maps outages to 503 but keeps genuine rejections as 400")
    void fallbackMapping() {
        TurnstileService service = service();

        assertThatThrownBy(() -> service.verifyFallback("t", null, new java.io.IOException("down")))
                .isInstanceOf(io.devflow.auth.internal.exception.BotProtectionUnavailableException.class);
        assertThatThrownBy(() -> service.verifyFallback("t", null, new BotChallengeFailedException()))
                .isInstanceOf(BotChallengeFailedException.class);
        verify(metrics).antiAbuse("turnstile", "unavailable");
    }

    @Test
    @DisplayName("Does nothing when disabled, and refuses to start when enabled without a secret")
    void configurationGuards() {
        properties.setEnabled(false);
        assertThatCode(() -> service().verify(null, null)).doesNotThrowAnyException();

        properties.setEnabled(true);
        properties.setSecretKey("");
        assertThatThrownBy(() -> service().validateConfiguration()).isInstanceOf(IllegalStateException.class);
    }

}
