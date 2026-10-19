package io.devflow.auth.internal.listener;

import io.devflow.auth.internal.repository.WorkspaceMemberRepository;
import io.devflow.common.event.WorkspaceMembershipCheckRequestedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Auth-owned membership lookup for synchronous callers in other modules. */
@Component
public class WorkspaceMembershipCheckListener {

    private final WorkspaceMemberRepository workspaceMemberRepository;

    public WorkspaceMembershipCheckListener(WorkspaceMemberRepository workspaceMemberRepository) {
        this.workspaceMemberRepository = workspaceMemberRepository;
    }

    @EventListener
    public void onMembershipCheckRequested(WorkspaceMembershipCheckRequestedEvent event) {
        boolean member = workspaceMemberRepository.existsByWorkspaceIdAndUserId(
                event.workspaceId(), event.userId());
        event.recordDecision(member);
    }
}
