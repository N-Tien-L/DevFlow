package io.devflow.auth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request payload carrying the token from the verification link.
 */
public record VerifyEmailRequest(
        @NotBlank(message = "Verification token is required")
        @Size(max = 128, message = "Verification token is malformed")
        String token
) {

    @Override
    public String toString() {
        return "VerifyEmailRequest[token=***]";
    }
}
