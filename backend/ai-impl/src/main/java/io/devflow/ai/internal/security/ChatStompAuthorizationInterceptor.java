package io.devflow.ai.internal.security;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * Inbound guard for the chat destinations (T-034).
 *
 * <p>Spring resolves a client SEND to {@code /user/{name}/queue/chat} into that user's sessions, so without
 * this guard any signed-in client could inject forged CHUNK or TASK_PROPOSAL frames into another user's chat
 * panel. Only the server publishes to chat queues. Clients may:
 * <ul>
 *   <li>SUBSCRIBE to exactly {@code /user/queue/chat} (resolved to their own sessions);</li>
 *   <li>SEND to exactly {@code /app/chat} and {@code /app/chat/cancel}.</li>
 * </ul>
 * Every other frame addressing a chat destination is rejected.
 */
@Component
public class ChatStompAuthorizationInterceptor implements ChannelInterceptor {

    static final String USER_CHAT_QUEUE = "/user/queue/chat";
    static final String CHAT_SEND = "/app/chat";
    static final String CHAT_CANCEL = "/app/chat/cancel";

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        String destination = accessor.getDestination();
        if (destination == null || !isChatDestination(destination)) {
            return message;
        }
        StompCommand command = accessor.getCommand();
        if (command == StompCommand.SUBSCRIBE && USER_CHAT_QUEUE.equals(destination)) {
            return message;
        }
        if (command == StompCommand.SEND && (CHAT_SEND.equals(destination) || CHAT_CANCEL.equals(destination))) {
            return message;
        }
        throw new MessageDeliveryException("ACCESS_DENIED");
    }

    /**
     * True for {@code /app/chat*}, {@code /queue/chat*}, {@code /topic/chat*} and their {@code /user/...}
     * forms, with or without a user name segment.
     */
    static boolean isChatDestination(String destination) {
        String target = destination;
        if (target.startsWith("/user/")) {
            target = target.substring("/user".length());
            if (!target.startsWith("/queue/") && !target.startsWith("/topic/")) {
                int nextSegment = target.indexOf('/', 1);
                target = nextSegment < 0 ? "" : target.substring(nextSegment);
            }
        }
        return target.startsWith("/app/chat") || target.startsWith("/queue/chat")
                || target.startsWith("/topic/chat");
    }
}
