package io.devflow.ai.internal.security;

import io.devflow.ai.internal.exception.AiApiException;
import io.devflow.common.event.WorkspaceMembershipCheckRequestedEvent;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * IDOR guard: asks the Auth module (owner of membership) through the synchronous typed event contract in
 * {@code :common}, never through Auth internals. Fails closed: no or several decisions -> 503.
 */
@Component
public class WorkspaceMembershipChecker {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceMembershipChecker.class);

    private final ApplicationEventPublisher eventPublisher;

    public WorkspaceMembershipChecker(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /**
     * @throws AiApiException 403 when not a member, 503 when the decision is unavailable
     */
    public void requireMember(UUID userId, UUID workspaceId) {
        UUID correlationId = correlationId();
        WorkspaceMembershipCheckRequestedEvent event =
                new WorkspaceMembershipCheckRequestedEvent(correlationId, userId, workspaceId);
        try {
            eventPublisher.publishEvent(event);
        } catch (RuntimeException failure) {
            log.error("Workspace membership check failed correlationId={} exceptionType={}",
                    correlationId, failure.getClass().getSimpleName());
            throw AiApiException.membershipCheckUnavailable();
        }
        switch (event.responseCount() == 1 ? event.decision() : WorkspaceMembershipCheckRequestedEvent.Decision.AMBIGUOUS) {
            case MEMBER -> {
            }
            case NOT_MEMBER -> throw AiApiException.forbidden();
            case PENDING, AMBIGUOUS -> {
                log.error("Workspace membership check returned {} decisions correlationId={}",
                        event.responseCount(), correlationId);
                throw AiApiException.membershipCheckUnavailable();
            }
        }
    }

    private static UUID correlationId() {
        String value = MDC.get("correlationId");
        if (value != null) {
            try {
                return UUID.fromString(value);
            } catch (IllegalArgumentException ignored) {
                // Not a UUID: use a server-generated one.
            }
        }
        return UUID.randomUUID();
    }
}
