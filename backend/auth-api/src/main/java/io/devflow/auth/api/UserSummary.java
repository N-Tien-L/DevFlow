package io.devflow.auth.api;

import java.util.UUID;

/**
 * Read-only view of a user exposed to other modules and clients.
 */
public record UserSummary(UUID id, String email, String fullName, String avatarUrl) {

    public UserSummary(UUID id, String email, String fullName) {
        this(id, email, fullName, null);
    }

    public String displayName() {
        return fullName;
    }
}
