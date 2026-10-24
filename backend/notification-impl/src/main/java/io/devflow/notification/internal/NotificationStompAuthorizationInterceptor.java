package io.devflow.notification.internal;

import io.devflow.common.security.AuthenticatedSession;
import java.time.Instant;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/** Reject raw broker queues, guessed user destinations, and client publication. */
@Component
public class NotificationStompAuthorizationInterceptor implements ChannelInterceptor {
    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        var accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) { return message; }
        String destination = accessor.getDestination();
        if (destination == null) { return message; }
        // The simple broker supports wildcard subscriptions. A raw /queue/** could
        // otherwise read every recipient's physical notification queue.
        boolean rawQueue = destination.startsWith("/queue");
        if (!rawQueue && !destination.contains("notifications")) { return message; }
        if (accessor.getCommand() == StompCommand.SUBSCRIBE
                && destination.equals("/user/queue/notifications")) {
            var attributes = accessor.getSessionAttributes();
            Object identity = attributes == null ? null : attributes.get(AuthenticatedSession.STOMP_SESSION_ATTRIBUTE);
            if (identity instanceof AuthenticatedSession session && session.expiresAt().isAfter(Instant.now())
                    && accessor.getUser() != null && accessor.getUser().getName().equals(session.userId().toString())) {
                return message;
            }
        }
        throw new MessageDeliveryException("ACCESS_DENIED");
    }
}
