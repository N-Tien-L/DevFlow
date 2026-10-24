package io.devflow.auth.api;

import io.devflow.auth.api.validation.MaxUtf8Bytes;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Request payload for user authentication via email and password.
 */
public record LoginRequest(
        @NotBlank(message = "Email is required")
        @Email(message = "Invalid email format")
        String email,

        // No valid password is longer: registration and reset enforce the same BCrypt byte limit.
        @NotBlank(message = "Password is required")
        @MaxUtf8Bytes(value = MaxUtf8Bytes.BCRYPT_MAX, message = "Invalid email or password")
        String password
) {}
