package io.devflow.ai.internal.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpTimeoutException;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.ResourceAccessException;

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
}
