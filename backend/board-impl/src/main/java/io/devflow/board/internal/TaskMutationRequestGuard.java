package io.devflow.board.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Applies the shared actor quota and server-side Turnstile verification before task writes. */
@Component
public class TaskMutationRequestGuard {

    private static final int MAX_TOKEN_LENGTH = 2_048;
    private static final int MAX_RESPONSE_BYTES = 16_384;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);

    private final AuthenticatedActorProvider actorProvider;
    private final BoardRateLimiter rateLimiter;
    private final ObjectMapper objectMapper;
    private final boolean turnstileEnabled;
    private final String secretKey;
    private final URI siteverifyUri;
    private final HttpClient httpClient;

    @Autowired
    public TaskMutationRequestGuard(
            AuthenticatedActorProvider actorProvider,
            BoardRateLimiter rateLimiter,
            ObjectMapper objectMapper,
            @Value("${devflow.turnstile.enabled:true}") boolean turnstileEnabled,
            @Value("${devflow.turnstile.secret-key:}") String secretKey,
            @Value("${devflow.turnstile.siteverify-url:https://challenges.cloudflare.com/turnstile/v0/siteverify}")
                    String siteverifyUrl) {
        this(actorProvider, rateLimiter, objectMapper, turnstileEnabled, secretKey,
                URI.create(siteverifyUrl), HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
    }

    TaskMutationRequestGuard(
            AuthenticatedActorProvider actorProvider,
            BoardRateLimiter rateLimiter,
            ObjectMapper objectMapper,
            boolean turnstileEnabled,
            String secretKey,
            URI siteverifyUri,
            HttpClient httpClient) {
        this.actorProvider = actorProvider;
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
        this.turnstileEnabled = turnstileEnabled;
        this.secretKey = secretKey;
        this.siteverifyUri = siteverifyUri;
        this.httpClient = httpClient;
    }

    /** Call once from each authenticated create/update/delete/move request, before opening a DB transaction. */
    public void authorizeMutation(String token, String remoteAddress) {
        UUID actorId = actorProvider.requireUserId();
        rateLimiter.check(actorId);
        if (!turnstileEnabled) {
            return;
        }
        if (!StringUtils.hasText(token) || token.length() > MAX_TOKEN_LENGTH) {
            throw BoardApiException.botChallengeRequired();
        }
        if (!StringUtils.hasText(secretKey)) {
            throw BoardApiException.botProtectionUnavailable();
        }

        HttpRequest request = HttpRequest.newBuilder(siteverifyUri)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(token, remoteAddress)))
                .build();
        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                try (InputStream ignored = response.body()) {
                    // Close the response without logging or retaining a provider error body.
                }
                throw BoardApiException.botProtectionUnavailable();
            }
            JsonNode result;
            try (InputStream body = response.body()) {
                byte[] bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bytes.length > MAX_RESPONSE_BYTES) {
                    throw BoardApiException.botProtectionUnavailable();
                }
                result = objectMapper.readTree(bytes);
            }
            if (result == null || !result.path("success").asBoolean(false)) {
                throw BoardApiException.botChallengeRejected();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw BoardApiException.botProtectionUnavailable();
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof BoardApiException boardApiException) {
                throw boardApiException;
            }
            throw BoardApiException.botProtectionUnavailable();
        }
    }

    private String form(String token, String remoteAddress) {
        String body = "secret=" + encode(secretKey) + "&response=" + encode(token);
        if (StringUtils.hasText(remoteAddress)) {
            body += "&remoteip=" + encode(remoteAddress);
        }
        return body;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
