package io.devflow.auth.internal;

import io.devflow.auth.api.AuthApi;
import io.devflow.auth.api.UserSummary;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Auth module core service. Owns: accounts, workspaces, membership, permissions
 * (ARCHITECTURE.md Section 3).
 *
 * <p>TODO(PRODUCT_SPEC Section 5.1 — Accounts &amp; workspaces): implement sign up/login,
 * workspace creation, and member invites against User/Workspace/Membership entities
 * (each extending {@code io.devflow.common.entity.BaseEntity}).
 */
@Service
public class AuthService implements AuthApi {

    @Override
    public Optional<UserSummary> findUser(UUID userId) {
        // TODO(PRODUCT_SPEC 5.1): look up the User entity and map it to a UserSummary.
        throw new UnsupportedOperationException("Not implemented yet — scaffold stub");
    }

    @Override
    public boolean isWorkspaceMember(UUID userId, UUID workspaceId) {
        // TODO(PRODUCT_SPEC 5.1): check the Membership table for (userId, workspaceId).
        throw new UnsupportedOperationException("Not implemented yet — scaffold stub");
    }
}
