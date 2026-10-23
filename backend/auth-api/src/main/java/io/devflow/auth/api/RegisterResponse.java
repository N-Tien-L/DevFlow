package io.devflow.auth.api;

/**
 * Response returned after a successful registration. No tokens are issued until the email is verified.
 */
public record RegisterResponse(
        String email,
        String status,
        String message
) {}
