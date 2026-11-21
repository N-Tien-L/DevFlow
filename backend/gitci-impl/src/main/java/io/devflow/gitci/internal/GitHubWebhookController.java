package io.devflow.gitci.internal;

import jakarta.servlet.http.HttpServletRequest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** GitHub repository webhooks authenticate with their own HMAC, not a user session. */
@RestController
class GitHubWebhookController {
    private final GitHubWebhookProperties properties;
    private final GitHubWebhookService service;
    private final Semaphore inFlight;
    private final MeterRegistry metrics;
    private final WebhookQuota quota = new WebhookQuota();

    GitHubWebhookController(GitHubWebhookProperties properties, GitHubWebhookService service, MeterRegistry metrics) {
        this.properties = properties;
        this.service = service;
        this.inFlight = new Semaphore(properties.getMaxInFlight());
        this.metrics = metrics;
    }

    @PostMapping("/api/v1/gitci/webhook/github")
    ResponseEntity<?> receive(HttpServletRequest request) {
        if (!properties.isEnabled()) return problem(request, 503, "WEBHOOK_UNAVAILABLE");
        if (!quota.allow(request.getRemoteAddr())) return problem(request, 429, "WEBHOOK_RATE_LIMITED");
        if (!inFlight.tryAcquire()) return problem(request, 503, "WEBHOOK_UNAVAILABLE");
        Timer.Sample sample = Timer.start(metrics);
        try {
            validateMedia(request);
            Map<String, String[]> parameters = request.getParameterMap();
            if (parameters.size() != 1 || !parameters.containsKey("repositoryId")
                    || parameters.get("repositoryId").length != 1)
                throw new WebhookFailure(400, "WEBHOOK_INVALID_SELECTOR");
            UUID id;
            try {
                String selector = parameters.get("repositoryId")[0];
                id = UUID.fromString(selector);
                if (!id.toString().equalsIgnoreCase(selector)) throw new IllegalArgumentException();
            } catch (IllegalArgumentException ex) { throw new WebhookFailure(400, "WEBHOOK_INVALID_SELECTOR"); }
            if (request.getContentLengthLong() > properties.getMaxBodyBytes())
                throw new WebhookFailure(413, "WEBHOOK_TOO_LARGE");
            byte[] body = request.getInputStream().readNBytes(properties.getMaxBodyBytes() + 1);
            if (body.length > properties.getMaxBodyBytes()) throw new WebhookFailure(413, "WEBHOOK_TOO_LARGE");
            String signature = singleHeader(request, "X-Hub-Signature-256", false);
            String delivery = singleHeader(request, "X-GitHub-Delivery", true);
            String event = singleHeader(request, "X-GitHub-Event", true);
            int result = service.receive(id, signature, delivery, event, body);
            metrics.counter("devflow.gitci.webhook.requests", "outcome", result == 200 ? "ping" : "no_content", "code", "none").increment();
            return result == 200 ? ResponseEntity.ok(Map.of("status", "ok")) : ResponseEntity.noContent().build();
        } catch (WebhookFailure ex) {
            return problem(request, ex.status, ex.code);
        } catch (IOException ex) {
            return problem(request, 400, "WEBHOOK_INVALID_BODY");
        } catch (RuntimeException ex) {
            return problem(request, 500, "WEBHOOK_ERROR");
        } finally {
            sample.stop(metrics.timer("devflow.gitci.webhook.duration"));
            inFlight.release();
        }
    }

    private static String singleHeader(HttpServletRequest request, String name, boolean later) {
        List<String> values = Collections.list(request.getHeaders(name));
        if (values.size() != 1) {
            if (!later) return null;
            // Validate event metadata only after authentication, by supplying an invalid value.
            return "";
        }
        return values.get(0);
    }

    private static void validateMedia(HttpServletRequest request) {
        String encoding = request.getHeader(HttpHeaders.CONTENT_ENCODING);
        if (encoding != null && !encoding.equalsIgnoreCase("identity"))
            throw new WebhookFailure(415, "WEBHOOK_UNSUPPORTED_MEDIA");
        try {
            MediaType type = MediaType.parseMediaType(request.getContentType());
            if (!MediaType.APPLICATION_JSON.isCompatibleWith(type)
                    || (type.getCharset() != null && !StandardCharsets.UTF_8.equals(type.getCharset())))
                throw new IllegalArgumentException();
        } catch (RuntimeException ex) { throw new WebhookFailure(415, "WEBHOOK_UNSUPPORTED_MEDIA"); }
    }

    private ResponseEntity<ProblemDetail> problem(HttpServletRequest request, int status, String code) {
        metrics.counter("devflow.gitci.webhook.requests", "outcome", "rejected", "code", code).increment();
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), "Webhook request could not be processed.");
        detail.setProperty("code", code);
        detail.setProperty("timestamp", Instant.now());
        detail.setProperty("correlationId", org.slf4j.MDC.get("correlationId"));
        detail.setInstance(java.net.URI.create("/api/v1/gitci/webhook/github"));
        var response = ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (status == 429 || status == 503) response.header(HttpHeaders.RETRY_AFTER, "5");
        return response.body(detail);
    }
}
