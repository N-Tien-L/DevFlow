package io.devflow.ai.internal.exception;

/**
 * The LLM provider could not produce an answer (timeout, outage, rejected call or open circuit).
 * Mapped to RFC 7807 503; the message is generic and safe to show to clients.
 */
public class AiProviderUnavailableException extends RuntimeException {

    private final long retryAfterSeconds;

    public AiProviderUnavailableException(long retryAfterSeconds, Throwable cause) {
        super("The AI assistant is temporarily unavailable. Please try again later.", cause);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
