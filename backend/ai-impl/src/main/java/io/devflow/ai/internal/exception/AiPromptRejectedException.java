package io.devflow.ai.internal.exception;

/** A prompt was refused before reaching the provider (empty or over the configured length). */
public class AiPromptRejectedException extends RuntimeException {

    private final String code;

    public AiPromptRejectedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
