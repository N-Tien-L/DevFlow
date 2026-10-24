package io.devflow.ai.internal.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResponseErrorHandler;

/**
 * Classifies provider HTTP errors for the retry policy without copying the response body into the
 * exception: Spring AI's default handler embeds the body in the message, which can echo prompt
 * fragments into logs. 408, 429 and 5xx are transient; every other 4xx is not.
 *
 * <p>OpenAI also answers an exhausted quota with 429 ({@code error.code/type = insufficient_quota}). That
 * is fixed until billing changes, so it is non-transient: not retried, and ignored by the circuit breaker
 * like other rejected calls. Only that classification is taken from the body; at most
 * {@link #MAX_ERROR_BODY_BYTES} are read and nothing from the body reaches logs or exceptions.
 */
final class ProviderResponseErrorHandler implements ResponseErrorHandler {

    static final String INSUFFICIENT_QUOTA = "insufficient_quota";
    static final int MAX_ERROR_BODY_BYTES = 8 * 1024;

    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public boolean hasError(ClientHttpResponse response) throws IOException {
        return response.getStatusCode().isError();
    }

    @Override
    public void handleError(URI url, HttpMethod method, ClientHttpResponse response) throws IOException {
        HttpStatusCode status = response.getStatusCode();
        if (status.value() == 429 && isQuotaExhausted(response)) {
            throw new NonTransientProviderException(429, INSUFFICIENT_QUOTA);
        }
        throw classify(status);
    }

    static RuntimeException classify(HttpStatusCode status) {
        int code = status.value();
        if (code == 408 || code == 429 || status.is5xxServerError()) {
            return new TransientProviderException(code);
        }
        return new NonTransientProviderException(code, null);
    }

    static boolean isQuotaExhausted(byte[] errorBody) {
        try {
            JsonNode error = JSON.readTree(errorBody).path("error");
            return INSUFFICIENT_QUOTA.equals(error.path("code").asText(null))
                    || INSUFFICIENT_QUOTA.equals(error.path("type").asText(null));
        } catch (IOException | RuntimeException unreadable) {
            // Truncated at the size cap or not JSON: treat as an ordinary, retryable rate limit.
            return false;
        }
    }

    private static boolean isQuotaExhausted(ClientHttpResponse response) {
        try (InputStream body = response.getBody()) {
            return isQuotaExhausted(body.readNBytes(MAX_ERROR_BODY_BYTES));
        } catch (IOException unreadable) {
            return false;
        }
    }

    /** Message carries the status code only. */
    static final class TransientProviderException extends TransientAiException {

        private final int status;

        TransientProviderException(int status) {
            super("AI provider responded with HTTP " + status);
            this.status = status;
        }

        int status() {
            return status;
        }
    }

    /** Message carries the status code and, for known cases, a fixed reason constant. */
    static final class NonTransientProviderException extends NonTransientAiException {

        private final int status;
        private final String reason;

        NonTransientProviderException(int status, String reason) {
            super("AI provider responded with HTTP " + status + (reason == null ? "" : " (" + reason + ")"));
            this.status = status;
            this.reason = reason;
        }

        int status() {
            return status;
        }

        String reason() {
            return reason;
        }
    }
}
