package io.devflow.auth.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request payload for creating a personal access token for an MCP client.
 *
 * @param expiresInDays lifetime in days (1-365); {@code null} creates a token that never expires
 */
public record CreatePersonalAccessTokenRequest(
        @NotBlank(message = "Token name is required")
        @Size(max = 100, message = "Token name must not exceed 100 characters")
        String name,

        @Min(value = 1, message = "Expiry must be between 1 and 365 days")
        @Max(value = 365, message = "Expiry must be between 1 and 365 days")
        Integer expiresInDays
) {}
