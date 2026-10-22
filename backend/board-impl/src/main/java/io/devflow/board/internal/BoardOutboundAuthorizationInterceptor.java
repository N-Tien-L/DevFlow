package io.devflow.board.internal;

import io.devflow.common.security.AuthenticatedSession;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.stereotype.Component;

/** Rechecks current workspace membership and token expiry immediately before WebSocket delivery. */
@Component
public class BoardOutboundAuthorizationInterceptor implements ExecutorChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(BoardOutboundAuthorizationInterceptor.class);
    private static final String BOARD_TOPIC_PREFIX = "/topic/boards/";

    private final BoardSubscriptionRegistry subscriptionRegistry;
    private final WorkspaceMembershipPermissionService permissionService;
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    public BoardOutboundAuthorizationInterceptor(
            BoardSubscriptionRegistry subscriptionRegistry,
            WorkspaceMembershipPermissionService permissionService,
            ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.subscriptionRegistry = subscriptionRegistry;
        this.permissionService = permissionService;
        this.meterRegistryProvider = meterRegistryProvider;
    }

    @Override
    public Message<?> beforeHandle(
            Message<?> message,
            MessageChannel channel,
            org.springframework.messaging.MessageHandler handler) {
        var accessor = org.springframework.messaging.simp.SimpMessageHeaderAccessor.getAccessor(
                message, org.springframework.messaging.simp.SimpMessageHeaderAccessor.class);
        if (accessor == null
                || accessor.getMessageType() != SimpMessageType.MESSAGE
                || accessor.getDestination() == null
                || !accessor.getDestination().startsWith(BOARD_TOPIC_PREFIX)) {
            return message;
        }

        String sessionId = accessor.getSessionId();
        String subscriptionId = accessor.getSubscriptionId();
        String destination = accessor.getDestination();
        AuthenticatedSession identity = subscriptionRegistry.identityForSession(sessionId);
        UUID boardId = parseCanonicalBoardId(destination);
        boolean canonical = boardId != null;
        BoardSubscription trackedSubscription = canonical
                ? subscriptionRegistry.find(sessionId, subscriptionId) : null;
        boolean registered = identity != null && canonical && subscriptionRegistry.isAuthorized(sessionId, identity,
                subscriptionId, boardId);
        if (!registered) {
            record("denied");
            return null;
        }

        BoardSubscription subscription = subscriptionRegistry.find(sessionId, subscriptionId);
        if (subscription == null || !isMember(identity, subscription.workspaceId())) {
            record("denied");
            return null;
        }
        record("accepted");
        return message;
    }

    private boolean isMember(AuthenticatedSession identity, UUID workspaceId) {
        try {
            return permissionService.isMember(identity.userId(), workspaceId);
        } catch (RuntimeException exception) {
            log.error("Board realtime delivery membership check unavailable userId={} exceptionType={}",
                    identity.userId(), exception.getClass().getSimpleName());
            return false;
        }
    }

    private static UUID parseCanonicalBoardId(String destination) {
        try {
            String suffix = destination.substring(BOARD_TOPIC_PREFIX.length());
            UUID boardId = UUID.fromString(suffix);
            return boardId.toString().equals(suffix) ? boardId : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    @Override
    public void afterMessageHandled(Message<?> message, MessageChannel channel,
            org.springframework.messaging.MessageHandler handler, Exception exception) {
        // SessionDisconnectEvent owns registry cleanup. A rejected delivery is intentionally
        // dropped here; it must not recursively publish an ERROR on the same outbound channel.
        if (exception instanceof TaskRejectedException) {
            record("failure");
        }
    }

    private void record(String outcome) {
        try {
            MeterRegistry registry = meterRegistryProvider.getIfAvailable();
            if (registry != null) {
                registry.counter("devflow.board.realtime.deliveries", "outcome", outcome).increment();
            }
        } catch (RuntimeException exception) {
            log.warn("Board realtime delivery metric failed exceptionType={}",
                    exception.getClass().getSimpleName());
        }
    }
}
