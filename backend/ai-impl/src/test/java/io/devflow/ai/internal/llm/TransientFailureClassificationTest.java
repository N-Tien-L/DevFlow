package io.devflow.ai.internal.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonParseException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

@DisplayName("Which provider failures are retried")
class TransientFailureClassificationTest {

    @Test
    void timeoutsAndConnectionFailuresAreTransient() {
        assertThat(AiChatGateway.isTransient(
                new ResourceAccessException("I/O error", new HttpTimeoutException("timed out")))).isTrue();
        // JdkClientHttpRequestFactory can surface its read timeout as a bare CancellationException.
        assertThat(AiChatGateway.isTransient(new CancellationException())).isTrue();
    }

    @Test
    void transportFailuresWhileReadingTheBodyAreTransient() {
        // RestClient wraps body-read failures: provider stalled or dropped mid-response.
        assertThat(AiChatGateway.isTransient(new RestClientException("Error while extracting response",
                new SocketTimeoutException("Read timed out")))).isTrue();
        assertThat(AiChatGateway.isTransient(new RestClientException("Error while extracting response",
                new IOException("closed")))).isTrue();
        assertThat(AiChatGateway.isTransient(new RestClientException("Error while extracting response",
                new CancellationException()))).isTrue();
    }

    @Test
    void malformedProviderPayloadIsNotRetried() {
        JsonParseException parse = new JsonParseException(null, "Unexpected end-of-input");
        assertThat(AiChatGateway.isTransient(new RestClientException("Error while extracting response",
                new HttpMessageNotReadableException("JSON parse error", parse, null)))).isFalse();
        assertThat(AiChatGateway.isTransient(new RestClientException("Error while extracting response", parse)))
                .isFalse();
    }

    @Test
    void quotaExhaustionIsDistinguishedFromRateLimiting() {
        assertThat(quota("{\"error\":{\"type\":\"insufficient_quota\",\"code\":\"insufficient_quota\"}}"))
                .isTrue();
        assertThat(quota("{\"error\":{\"type\":\"insufficient_quota\",\"code\":null}}")).isTrue();
        assertThat(quota("{\"error\":{\"type\":\"requests\",\"code\":\"rate_limit_exceeded\"}}")).isFalse();
        assertThat(quota("<html>Too Many Requests</html>")).isFalse();
        assertThat(quota("")).isFalse();
        // Bodies over the read cap arrive truncated and fall back to an ordinary, retryable 429.
        String huge = "{\"error\":{\"message\":\"" + "x".repeat(ProviderResponseErrorHandler.MAX_ERROR_BODY_BYTES)
                + "\",\"code\":\"insufficient_quota\"}}";
        byte[] truncated = Arrays.copyOf(huge.getBytes(StandardCharsets.UTF_8),
                ProviderResponseErrorHandler.MAX_ERROR_BODY_BYTES);
        assertThat(ProviderResponseErrorHandler.isQuotaExhausted(truncated)).isFalse();
    }

    @Test
    void quotaExhaustionIsNonTransientAndOmitsTheBody() {
        RuntimeException quota = new ProviderResponseErrorHandler.NonTransientProviderException(429,
                ProviderResponseErrorHandler.INSUFFICIENT_QUOTA);

        assertThat(AiChatGateway.isTransient(quota)).isFalse();
        assertThat(quota.getMessage()).isEqualTo("AI provider responded with HTTP 429 (insufficient_quota)");
    }

    @Test
    void retriesOnlyRetryableHttpStatuses() {
        for (int status : new int[] {408, 429, 500, 502, 503, 504}) {
            assertThat(AiChatGateway.isTransient(ProviderResponseErrorHandler.classify(HttpStatusCode.valueOf(status))))
                    .as("HTTP %d", status).isTrue();
        }
        for (int status : new int[] {400, 401, 403, 404, 422}) {
            assertThat(AiChatGateway.isTransient(ProviderResponseErrorHandler.classify(HttpStatusCode.valueOf(status))))
                    .as("HTTP %d", status).isFalse();
        }
    }

    @Test
    void unexpectedFailuresAreNotRetried() {
        assertThat(AiChatGateway.isTransient(new IllegalStateException("empty completion"))).isFalse();
    }

    private static boolean quota(String body) {
        return ProviderResponseErrorHandler.isQuotaExhausted(body.getBytes(StandardCharsets.UTF_8));
    }
}
