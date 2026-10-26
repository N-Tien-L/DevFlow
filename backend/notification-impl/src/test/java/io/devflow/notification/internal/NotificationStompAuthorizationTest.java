package io.devflow.notification.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.devflow.common.security.AuthenticatedSession;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.simp.user.*;
import org.springframework.messaging.support.MessageBuilder;

class NotificationStompAuthorizationTest {
    final NotificationStompAuthorizationInterceptor interceptor = new NotificationStompAuthorizationInterceptor();
    final UUID user = UUID.randomUUID();

    Message<?> frame(StompCommand command, String destination, Instant expiry, boolean authenticated) {
        var accessor = StompHeaderAccessor.create(command);
        accessor.setDestination(destination);
        accessor.setSessionId("session-1");
        accessor.setSubscriptionId("sub-1");
        accessor.setSessionAttributes(new HashMap<>(Map.of(AuthenticatedSession.STOMP_SESSION_ATTRIBUTE,
                new AuthenticatedSession(user, expiry))));
        if (authenticated) { accessor.setUser(() -> user.toString()); }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test void permitsOnlyAuthenticatedUnexpiredPersonalSubscription() {
        var valid = frame(StompCommand.SUBSCRIBE, "/user/queue/notifications", Instant.now().plusSeconds(60), true);
        assertThat(interceptor.preSend(valid, null)).isSameAs(valid);
        for (var invalid : java.util.List.of(
                frame(StompCommand.SUBSCRIBE, "/user/queue/notifications", Instant.now().minusSeconds(1), true),
                frame(StompCommand.SUBSCRIBE, "/user/queue/notifications", Instant.now().plusSeconds(60), false))) {
            assertThatThrownBy(() -> interceptor.preSend(invalid, null)).isInstanceOf(MessageDeliveryException.class);
        }
    }

    @ParameterizedTest @ValueSource(strings = {
        "/topic/notifications/other-user", "/queue/notifications", "/queue/notifications-userother-session", "/queue/**", "/queue/*",
        "/user/other-user/queue/notifications", "/user/queue/notifications/other-user"})
    void rejectsGuessedForeignAndPhysicalSubscriptions(String destination) {
        var message = frame(StompCommand.SUBSCRIBE, destination, Instant.now().plusSeconds(60), true);
        assertThatThrownBy(() -> interceptor.preSend(message, null)).isInstanceOf(MessageDeliveryException.class);
    }

    @Test void clientsCannotPublishNotifications() {
        var message = frame(StompCommand.SEND, "/user/queue/notifications", Instant.now().plusSeconds(60), true);
        assertThatThrownBy(() -> interceptor.preSend(message, null)).isInstanceOf(MessageDeliveryException.class);
    }

    @Test void frameworkUserDestinationResolverRoutesOnlyToTargetUsersSessions() {
        SimpUserRegistry registry = mock(SimpUserRegistry.class);
        SimpUser alice = mock(SimpUser.class);
        SimpSession aliceSession = mock(SimpSession.class);
        when(aliceSession.getId()).thenReturn("alice-session");
        when(alice.getSessions()).thenReturn(java.util.Set.of(aliceSession));
        when(registry.getUser("alice")).thenReturn(alice);
        var resolver = new DefaultUserDestinationResolver(registry);
        var headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setDestination("/user/alice/queue/notifications");
        var message = MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
        var result = resolver.resolveDestination(message);
        assertThat(result.getTargetDestinations()).containsExactly("/queue/notifications-useralice-session");
        verify(registry, never()).getUser("bob");
    }
}
