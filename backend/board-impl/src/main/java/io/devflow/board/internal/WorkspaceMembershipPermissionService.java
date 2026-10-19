package io.devflow.board.internal;

import io.devflow.common.event.WorkspaceMembershipCheckRequestedEvent;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/** Calls the Auth-owned membership check through the synchronous typed event contract. */
@Service
public class WorkspaceMembershipPermissionService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceMembershipPermissionService.class);

    private final ApplicationEventPublisher eventPublisher;

    public WorkspaceMembershipPermissionService(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public boolean isMember(UUID userId, UUID workspaceId) {
        UUID correlationId = BoardRequestContext.correlationId();
        WorkspaceMembershipCheckRequestedEvent event =
                new WorkspaceMembershipCheckRequestedEvent(correlationId, userId, workspaceId);
        try {
            eventPublisher.publishEvent(event);
        } catch (RuntimeException exception) {
            log.error("Workspace membership check failed; correlationId={}", correlationId);
            throw BoardApiException.unavailable();
        }

        if (event.responseCount() != 1) {
            log.error("Workspace membership check returned {} decisions; correlationId={}",
                    event.responseCount(), correlationId);
            throw BoardApiException.unavailable();
        }
        return switch (event.decision()) {
            case MEMBER -> true;
            case NOT_MEMBER -> false;
            case PENDING, AMBIGUOUS -> {
                log.error("Workspace membership check returned an invalid decision; correlationId={}", correlationId);
                throw BoardApiException.unavailable();
            }
        };
    }
}
