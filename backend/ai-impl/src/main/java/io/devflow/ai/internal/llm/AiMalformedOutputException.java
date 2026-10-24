package io.devflow.ai.internal.llm;

/**
 * The provider answered, but the answer did not convert to the requested type or failed validation.
 * The message names the failed rule only, never answer content, so it is safe to log.
 */
public class AiMalformedOutputException extends RuntimeException {

    public AiMalformedOutputException(String reason) {
        super(reason);
    }
}
