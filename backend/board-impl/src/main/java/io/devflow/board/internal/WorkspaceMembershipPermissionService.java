package io.devflow.board.internal;

import io.devflow.common.event.WorkspaceMembershipCheckRequestedEvent;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Calls the Auth-owned membership check through the synchronous typed event contract.
 *
 * <p>Takes the checked user's {@link UserWriteLock} shared first and keeps it for the rest of the transaction, so
 * an account-deletion cleanup waits for Board writes that already passed this check (T-009).
 */
@Service
public class WorkspaceMembershipPermissionService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceMembershipPermissionService.class);

    private final ApplicationEventPublisher eventPublisher;
    private final UserWriteLock userWriteLock;

    public WorkspaceMembershipPermissionService(ApplicationEventPublisher eventPublisher, UserWriteLock userWriteLock) {
        this.eventPublisher = eventPublisher;
        this.userWriteLock = userWriteLock;
    }

    public boolean isMember(UUID userId, UUID workspaceId) {
        userWriteLock.lockShared(userId);
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
