package io.devflow.ai.internal.llm;

import java.io.IOException;
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
 */
final class ProviderResponseErrorHandler implements ResponseErrorHandler {

    @Override
    public boolean hasError(ClientHttpResponse response) throws IOException {
        return response.getStatusCode().isError();
    }

    @Override
    public void handleError(URI url, HttpMethod method, ClientHttpResponse response) throws IOException {
        throw classify(response.getStatusCode());
    }

    static RuntimeException classify(HttpStatusCode status) {
        int code = status.value();
        if (code == 408 || code == 429 || status.is5xxServerError()) {
            return new TransientProviderException(code);
        }
        return new NonTransientProviderException(code);
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

    /** Message carries the status code only. */
    static final class NonTransientProviderException extends NonTransientAiException {

        private final int status;

        NonTransientProviderException(int status) {
            super("AI provider responded with HTTP " + status);
            this.status = status;
        }

        int status() {
            return status;
        }
    }
}
