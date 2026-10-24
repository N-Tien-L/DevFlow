package io.devflow.auth.api;

import io.devflow.auth.api.validation.MaxUtf8Bytes;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request payload carrying the token from the password reset email and the new password.
 * The password rules are the same as at registration.
 */
public record ResetPasswordRequest(
        @NotBlank(message = "Reset token is required")
        @Size(max = 128, message = "Reset token is malformed")
        String token,

        // BCrypt only uses the first 72 bytes of input, hence the upper bound.
        @NotBlank(message = "Password is required")
        @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
        @MaxUtf8Bytes(value = MaxUtf8Bytes.BCRYPT_MAX, message = "Password must not exceed 72 bytes (accented characters count as 2 or more)")
        @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).*$",
                message = "Password must contain at least one lowercase letter, one uppercase letter and one digit")
        String newPassword
) {

    @Override
    public String toString() {
        return "ResetPasswordRequest[token=***, newPassword=***]";
    }
}
