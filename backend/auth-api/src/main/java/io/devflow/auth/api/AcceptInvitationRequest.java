package io.devflow.auth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request payload carrying the token from a workspace invitation email.
 */
public record AcceptInvitationRequest(
        @NotBlank(message = "Invitation token is required")
        @Size(max = 128, message = "Invitation token is malformed")
        String token
) {

    @Override
    public String toString() {
        return "AcceptInvitationRequest[token=***]";
    }
}
