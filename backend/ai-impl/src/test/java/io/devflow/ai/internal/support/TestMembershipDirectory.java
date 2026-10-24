package io.devflow.ai.internal.support;

import io.devflow.common.event.WorkspaceMembershipCheckRequestedEvent;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Stands in for the Auth module's synchronous membership listener in ai-impl slice tests: answers
 * {@link WorkspaceMembershipCheckRequestedEvent} from an in-memory set of (user, workspace) pairs.
 */
@Component
public class TestMembershipDirectory {

    private final Set<String> members = ConcurrentHashMap.newKeySet();

    public void addMember(UUID userId, UUID workspaceId) {
        members.add(key(userId, workspaceId));
    }

    @EventListener
    void decide(WorkspaceMembershipCheckRequestedEvent event) {
        event.recordDecision(members.contains(key(event.userId(), event.workspaceId())));
    }

    private static String key(UUID userId, UUID workspaceId) {
        return userId + "/" + workspaceId;
    }
}
