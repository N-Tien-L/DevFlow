package io.devflow.auth.api;

import jakarta.validation.constraints.NotBlank;

/**
 * Request payload for refreshing an expired access token using a refresh token.
 */
public record RefreshTokenRequest(
        @NotBlank(message = "Refresh token is required")
        String refreshToken
) {}
