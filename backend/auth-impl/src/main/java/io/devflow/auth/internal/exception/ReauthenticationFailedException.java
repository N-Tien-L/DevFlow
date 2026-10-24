package io.devflow.auth.internal.exception;

/**
 * Thrown when the password re-entered to confirm a sensitive action is wrong. Mapped to 403 (not 401), so clients
 * do not mistake it for an expired session and sign the user out.
 */
public class ReauthenticationFailedException extends RuntimeException {

    public ReauthenticationFailedException() {
        super("The password is incorrect.");
    }
}
