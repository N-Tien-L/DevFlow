package io.devflow.auth.internal.exception;

/**
 * Thrown when a Turnstile token is missing or Cloudflare rejects it (HTTP 400, code {@code BOT_CHALLENGE_FAILED}).
 */
public class BotChallengeFailedException extends RuntimeException {

    public BotChallengeFailedException() {
        super("Security check failed. Please complete the challenge and try again.");
    }
}
