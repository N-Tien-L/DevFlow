package io.devflow.ai.internal.support;

import io.devflow.auth.api.AuthApi;
import io.devflow.auth.api.UserSummary;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Stands in for the Auth module's {@link AuthApi} user lookup in ai-impl slice tests. Membership is not
 * answered here: the AI module checks it through {@link TestMembershipDirectory}'s event contract.
 */
@Component
public class TestUserDirectory implements AuthApi {

    private final Map<UUID, UserSummary> users = new ConcurrentHashMap<>();

    public UUID addUser(String fullName) {
        UUID id = UUID.randomUUID();
        users.put(id, new UserSummary(id, id + "@devflow.test", fullName));
        return id;
    }

    @Override
    public Optional<UserSummary> findUser(UUID userId) {
        return Optional.ofNullable(users.get(userId));
    }

    @Override
    public boolean isWorkspaceMember(UUID userId, UUID workspaceId) {
        throw new UnsupportedOperationException("The AI module checks membership through the event contract");
    }
}
