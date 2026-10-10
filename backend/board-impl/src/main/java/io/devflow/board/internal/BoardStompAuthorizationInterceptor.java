package io.devflow.board.internal;

import io.devflow.board.internal.repository.BoardRepository;
import io.devflow.common.security.AuthenticatedSession;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Validates Board subscriptions before they reach the in-memory broker. */
@Component
public class BoardStompAuthorizationInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(BoardStompAuthorizationInterceptor.class);
    private static final String BOARD_TOPIC_PREFIX = "/topic/boards/";
    private static final int MAX_SUBSCRIPTIONS_PER_SESSION = 10;

    private final BoardRepository boardRepository;
    private final WorkspaceMembershipPermissionService permissionService;
    private final BoardSubscriptionRegistry subscriptionRegistry;
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    public BoardStompAuthorizationInterceptor(
            BoardRepository boardRepository,
            WorkspaceMembershipPermissionService permissionService,
            BoardSubscriptionRegistry subscriptionRegistry,
            ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.boardRepository = boardRepository;
        this.permissionService = permissionService;
        this.subscriptionRegistry = subscriptionRegistry;
        this.meterRegistryProvider = meterRegistryProvider;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }

        return switch (accessor.getCommand()) {
            case SUBSCRIBE -> authorizeBoardSubscription(message, accessor);
            case UNSUBSCRIBE -> unregister(message, accessor);
            case DISCONNECT -> disconnect(message, accessor);
            case SEND -> authorizeBoardSend(message, accessor);
            default -> message;
        };
    }

    private Message<?> authorizeBoardSubscription(Message<?> message, StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith(BOARD_TOPIC_PREFIX)) {
            return message;
        }

        UUID boardId;
        try {
            String suffix = destination.substring(BOARD_TOPIC_PREFIX.length());
            boardId = UUID.fromString(suffix);
            if (!boardId.toString().equals(suffix)) {
                return reject(accessor, "INVALID_DESTINATION");
            }
        } catch (IllegalArgumentException exception) {
            return reject(accessor, "INVALID_DESTINATION");
        }

        String sessionId = accessor.getSessionId();
        String subscriptionId = accessor.getSubscriptionId();
        AuthenticatedSession identity = sessionIdentity(accessor);
        if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(subscriptionId) || identity == null
                || !identity.expiresAt().isAfter(java.time.Instant.now())) {
            return reject(accessor, "AUTH_REQUIRED");
        }
        if (subscriptionRegistry.find(sessionId, subscriptionId) != null) {
            return reject(accessor, "INVALID_DESTINATION");
        }

        var workspaceId = boardRepository.findWorkspaceIdByBoardId(boardId);
        if (workspaceId.isEmpty() || !isMember(identity, workspaceId.get())) {
            return reject(accessor, "ACCESS_DENIED");
        }
        if (subscriptionRegistry.countSubscriptionsForUser(identity.userId()) >= 25
                || subscriptionRegistry.countSubscriptionsForSession(sessionId) >= MAX_SUBSCRIPTIONS_PER_SESSION
                || subscriptionRegistry.activeSubscriptionCount() >= 200) {
            return reject(accessor, "RATE_LIMITED");
        }
        if (!subscriptionRegistry.register(sessionId, identity, subscriptionId,
                new BoardSubscription(boardId, workspaceId.get()))) {
            return reject(accessor, "INVALID_DESTINATION");
        }
        recordCounter("devflow.websocket.board.subscriptions", "accepted");
        return message;
    }

    private boolean isMember(AuthenticatedSession identity, UUID workspaceId) {
        try {
            return permissionService.isMember(identity.userId(), workspaceId);
        } catch (RuntimeException exception) {
            log.error("Board subscription membership check unavailable boardUser={} exceptionType={}",
                    identity.userId(), exception.getClass().getSimpleName());
            return false;
        }
    }

    private Message<?> unregister(Message<?> message, StompHeaderAccessor accessor) {
        String sessionId = accessor.getSessionId();
        String subscriptionId = accessor.getSubscriptionId();
        if (StringUtils.hasText(sessionId) && StringUtils.hasText(subscriptionId)
                && subscriptionRegistry.find(sessionId, subscriptionId) != null) {
            subscriptionRegistry.unregister(sessionId, subscriptionId);
        }
        return message;
    }

    private Message<?> disconnect(Message<?> message, StompHeaderAccessor accessor) {
        subscriptionRegistry.onDisconnect(accessor);
        return message;
    }

    private Message<?> authorizeBoardSend(Message<?> message, StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination != null && (destination.startsWith(BOARD_TOPIC_PREFIX)
                || destination.startsWith("/app/boards"))) {
            return reject(accessor, "ACCESS_DENIED");
        }
        return message;
    }

    private Message<?> reject(StompHeaderAccessor accessor, String reason) {
        recordCounter("devflow.websocket.board.rejections", reason);
        log.warn("Board STOMP message rejected reason={} sessionId={}", reason, accessor.getSessionId());
        throw new MessageDeliveryException(reason);
    }

    private static AuthenticatedSession sessionIdentity(StompHeaderAccessor accessor) {
        if (accessor.getSessionAttributes() == null) {
            return null;
        }
        Object identity = accessor.getSessionAttributes().get(AuthenticatedSession.STOMP_SESSION_ATTRIBUTE);
        return identity instanceof AuthenticatedSession authenticatedSession ? authenticatedSession : null;
    }

    private void recordCounter(String metric, String outcome) {
        try {
            MeterRegistry registry = meterRegistryProvider.getIfAvailable();
            if (registry != null) {
                registry.counter(metric, "outcome", outcome).increment();
            }
        } catch (RuntimeException exception) {
            log.warn("Board STOMP metric failed metric={} exceptionType={}",
                    metric, exception.getClass().getSimpleName());
        }
    }
}
