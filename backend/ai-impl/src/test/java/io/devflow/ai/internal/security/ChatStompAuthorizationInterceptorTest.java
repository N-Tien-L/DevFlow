package io.devflow.ai.internal.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

@DisplayName("T-034: chat STOMP destination guard")
class ChatStompAuthorizationInterceptorTest {

    private final ChatStompAuthorizationInterceptor interceptor = new ChatStompAuthorizationInterceptor();

    @Test
    void allowsOwnChatQueueSubscriptionAndChatCommands() {
        assertAllowed(StompCommand.SUBSCRIBE, "/user/queue/chat");
        assertAllowed(StompCommand.SEND, "/app/chat");
        assertAllowed(StompCommand.SEND, "/app/chat/cancel");
    }

    @Test
    void ignoresOtherModulesDestinations() {
        assertAllowed(StompCommand.SUBSCRIBE, "/user/queue/notifications");
        assertAllowed(StompCommand.SUBSCRIBE, "/topic/boards/5b0ec1a4-6a43-4c0e-9d0c-1f1c4b8f1d10");
        assertAllowed(StompCommand.SEND, "/app/boards");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/user/6f1c2a7e-0b5e-4f7a-9a51-2b1d4c3e5f60/queue/chat",
            "/user/queue/chat",
            "/queue/chat",
            "/queue/chat-userabc123",
            "/topic/chat",
            "/app/chat/anything"
    })
    void rejectsClientPublicationToChatQueues(String destination) {
        assertDenied(StompCommand.SEND, destination);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/user/6f1c2a7e-0b5e-4f7a-9a51-2b1d4c3e5f60/queue/chat",
            "/queue/chat-userabc123",
            "/queue/chat*",
            "/topic/chat",
            "/user/queue/chat-other",
            "/app/chat"
    })
    void rejectsSubscriptionsOtherThanTheOwnChatQueue(String destination) {
        assertDenied(StompCommand.SUBSCRIBE, destination);
    }

    private void assertAllowed(StompCommand command, String destination) {
        Message<byte[]> message = frame(command, destination);
        assertThat(interceptor.preSend(message, null)).isSameAs(message);
    }

    private void assertDenied(StompCommand command, String destination) {
        assertThatThrownBy(() -> interceptor.preSend(frame(command, destination), null))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("ACCESS_DENIED");
    }

    private static Message<byte[]> frame(StompCommand command, String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setDestination(destination);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
