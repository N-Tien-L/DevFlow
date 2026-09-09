package io.devflow.auth.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Public interface of the Auth module (ARCHITECTURE.md Section 3: Auth owns accounts,
 * workspaces, membership, permissions). This is the ONLY way other modules may query
 * Auth state synchronously — everything else goes through the event bus (Section 4).
 *
 * <p>Implementations live in {@code auth-impl} and are never imported directly.
 */
public interface AuthApi {

    /** Looks up a user by id. Used by other modules to resolve assignees/authors. */
    Optional<UserSummary> findUser(UUID userId);

    /** Checks workspace membership — the building block of permission checks. */
    boolean isWorkspaceMember(UUID userId, UUID workspaceId);
}
