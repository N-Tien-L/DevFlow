package io.devflow.auth.internal.antiabuse;

import com.fasterxml.jackson.databind.JsonNode;
import io.devflow.auth.internal.exception.BotChallengeFailedException;
import io.devflow.auth.internal.exception.BotProtectionUnavailableException;
import io.devflow.auth.internal.support.AuthMetrics;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import jakarta.annotation.PostConstruct;
import java.net.http.HttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * Verifies Cloudflare Turnstile tokens server-side via the Siteverify API.
 *
 * <p>Calls are bounded by connect/read timeouts and wrapped in a circuit breaker. They are deliberately
 * not retried: a token is single-use, so a retry after a timeout that actually reached Cloudflare would
 * be rejected as a duplicate. Registration fails closed when Cloudflare cannot be reached.
 */
@Component
public class TurnstileService {

    static final String RESILIENCE_NAME = "turnstile";
    static final int MAX_TOKEN_LENGTH = 2048;

    private static final Logger log = LoggerFactory.getLogger(TurnstileService.class);

    private final TurnstileProperties properties;
    private final AuthMetrics metrics;
    private final RestClient restClient;

    @Autowired
    public TurnstileService(TurnstileProperties properties, AuthMetrics metrics, RestClient.Builder builder) {
        this(properties, metrics, builder.requestFactory(requestFactory(properties)).build());
    }

    TurnstileService(TurnstileProperties properties, AuthMetrics metrics, RestClient restClient) {
        this.properties = properties;
        this.metrics = metrics;
        this.restClient = restClient;
    }

    private static JdkClientHttpRequestFactory requestFactory(TurnstileProperties properties) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(properties.getConnectTimeout()).build());
        factory.setReadTimeout(properties.getReadTimeout());
        return factory;
    }

    @PostConstruct
    void validateConfiguration() {
        if (!properties.isEnabled()) {
            log.warn("Turnstile is disabled (devflow.turnstile.enabled=false); registration is not bot-protected. "
                    + "Set TURNSTILE_ENABLED=true and TURNSTILE_SECRET_KEY in production.");
            return;
        }
        if (!StringUtils.hasText(properties.getSecretKey())) {
            throw new IllegalStateException("devflow.turnstile.enabled=true requires devflow.turnstile.secret-key");
        }
    }

    /**
     * @throws BotChallengeFailedException       token missing, oversized or rejected by Cloudflare
     * @throws BotProtectionUnavailableException Siteverify unreachable, erroring or circuit open
     */
    @CircuitBreaker(name = RESILIENCE_NAME, fallbackMethod = "verifyFallback")
    public void verify(String token, String remoteIp) {
        if (!properties.isEnabled()) {
            return;
        }
        if (!StringUtils.hasText(token) || token.length() > MAX_TOKEN_LENGTH) {
            metrics.antiAbuse("turnstile", "missing");
            throw new BotChallengeFailedException();
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("secret", properties.getSecretKey());
        form.add("response", token);
        if (StringUtils.hasText(remoteIp)) {
            form.add("remoteip", remoteIp);
        }

        JsonNode result = restClient.post()
                .uri(properties.getSiteverifyUrl())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(JsonNode.class);

        if (result == null || !result.path("success").asBoolean(false)) {
            metrics.antiAbuse("turnstile", "rejected");
            // Error codes are Cloudflare enums (e.g. timeout-or-duplicate), never the token itself.
            log.info("Turnstile rejected a registration challenge: {}",
                    result == null ? "empty response" : result.path("error-codes"));
            throw new BotChallengeFailedException();
        }
        metrics.antiAbuse("turnstile", "passed");
    }

    @SuppressWarnings("unused") // invoked reflectively by Resilience4j
    void verifyFallback(String token, String remoteIp, Throwable cause) {
        if (cause instanceof BotChallengeFailedException rejected) {
            throw rejected;
        }
        metrics.antiAbuse("turnstile", "unavailable");
        log.error("Turnstile Siteverify unavailable: {}", cause.getClass().getSimpleName() + ": " + cause.getMessage());
        throw new BotProtectionUnavailableException();
    }
}
