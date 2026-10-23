package io.devflow.auth.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request payload for inviting someone to a workspace by email.
 *
 * @param role {@code MEMBER} (default when omitted) or {@code ADMIN}; only the workspace owner may invite admins
 */
public record CreateInvitationRequest(
        @NotBlank(message = "Email is required")
        @Email(message = "Invalid email format")
        @Size(max = 254, message = "Email must not exceed 254 characters")
        String email,

        @Pattern(regexp = "(?i)^(ADMIN|MEMBER)$", message = "Role must be ADMIN or MEMBER")
        String role
) {

    public CreateInvitationRequest(String email) {
        this(email, null);
    }

    @Override
    public String toString() {
        return "CreateInvitationRequest[role=" + role + "]";
    }
}
