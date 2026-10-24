package io.devflow.auth.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request payload for starting a password reset. The response never reveals whether the email has an account.
 */
public record ForgotPasswordRequest(
        @NotBlank(message = "Email is required")
        @Email(message = "Invalid email format")
        @Size(max = 254, message = "Email must not exceed 254 characters")
        String email,

        // Cloudflare Turnstile response token from the form; required when bot protection is on.
        @Size(max = 2048, message = "Security check token is too long")
        String turnstileToken
) {

    public ForgotPasswordRequest(String email) {
        this(email, null);
    }

    @Override
    public String toString() {
        return "ForgotPasswordRequest[email=***]";
    }
}
