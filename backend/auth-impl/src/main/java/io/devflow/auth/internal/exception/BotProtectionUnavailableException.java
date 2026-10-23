package io.devflow.auth.internal.exception;

/**
 * Thrown when Turnstile cannot be verified because Siteverify is unreachable or its circuit is open
 * (HTTP 503, code {@code BOT_PROTECTION_UNAVAILABLE}). Registration fails closed in this case.
 */
public class BotProtectionUnavailableException extends RuntimeException {

    public BotProtectionUnavailableException() {
        super("The security check is temporarily unavailable. Please try again shortly.");
    }
}
