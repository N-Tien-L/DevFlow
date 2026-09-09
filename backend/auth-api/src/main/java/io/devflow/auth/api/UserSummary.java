package io.devflow.auth.api;

import java.util.UUID;

/**
 * Read-only view of a user exposed to other modules. Deliberately minimal — internal Auth
 * entities (password hashes, roles, ...) never leave the module (ARCHITECTURE.md Section 10).
 */
public record UserSummary(UUID id, String email, String displayName) {
}
