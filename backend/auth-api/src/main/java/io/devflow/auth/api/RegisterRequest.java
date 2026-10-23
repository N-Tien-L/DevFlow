package io.devflow.auth.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request payload for registering a new user account.
 * The account stays {@code PENDING_VERIFICATION} until the emailed link is confirmed.
 */
public record RegisterRequest(
        @NotBlank(message = "Email is required")
        @Email(message = "Invalid email format")
        @Size(max = 254, message = "Email must not exceed 254 characters")
        String email,

        // BCrypt only uses the first 72 bytes of input, hence the upper bound.
        @NotBlank(message = "Password is required")
        @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
        @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).*$",
                message = "Password must contain at least one lowercase letter, one uppercase letter and one digit")
        String password,

        @NotBlank(message = "Full name is required")
        @Size(max = 255, message = "Full name must not exceed 255 characters")
        String fullName,

        // Cloudflare Turnstile response token from the registration form; required when bot protection is on.
        @Size(max = 2048, message = "Security check token is too long")
        String turnstileToken
) {

    public RegisterRequest(String email, String password, String fullName) {
        this(email, password, fullName, null);
    }

    @Override
    public String toString() {
        return "RegisterRequest[email=" + email + ", fullName=" + fullName + "]";
    }
}
