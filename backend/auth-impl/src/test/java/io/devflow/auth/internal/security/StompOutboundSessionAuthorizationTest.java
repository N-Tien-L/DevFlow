package io.devflow.auth.internal.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.jsonwebtoken.Claims;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

/** Exercises the configured outbound channel, including queued per-recipient broker deliveries. */
class StompOutboundSessionAuthorizationTest {
    private final UUID user = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-24T12:00:00Z");
    private final Clock clock = mock(Clock.class);
    private final JwtTokenProvider tokens = mock(JwtTokenProvider.class);
    private final SessionRevocationService revocation = mock(SessionRevocationService.class);
    private final StompSessionRegistry registry = new StompSessionRegistry();
    private final Map<String, Object> attributes = new HashMap<>();
    private final StompAuthenticationInterceptor authentication =
            new StompAuthenticationInterceptor(tokens, revocation, registry, clock);
    private final AtomicInteger received = new AtomicInteger();
    private ExecutorSubscribableChannel outbound;

    @BeforeEach void connectAndSubscribe() throws Exception {
        when(clock.instant()).thenReturn(now);
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn("session-1");
        when(socket.isOpen()).thenReturn(true);
        when(socket.getAttributes()).thenReturn(attributes);
        registry.decorate(mock(WebSocketHandler.class)).afterConnectionEstablished(socket);
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn(user.toString());
        when(claims.getIssuer()).thenReturn("devflow");
        when(claims.get("type", String.class)).thenReturn("access");
        when(claims.get("tv", Number.class)).thenReturn(3);
        when(claims.getExpiration()).thenReturn(Date.from(now.plusSeconds(60)));
        when(tokens.validateToken("test-token")).thenReturn(true);
        when(tokens.getClaims("test-token")).thenReturn(claims);
        when(tokens.getIssuer()).thenReturn("devflow");

        var connect = StompHeaderAccessor.create(StompCommand.CONNECT);
        connect.setSessionId("session-1");
        connect.setSessionAttributes(attributes);
        connect.addNativeHeader("Authorization", "Bearer test-token");
        connect.setLeaveMutable(true);
        authentication.preSend(MessageBuilder.createMessage(new byte[0], connect.getMessageHeaders()), null);
        var subscribe = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        subscribe.setSessionId("session-1");
        subscribe.setSessionAttributes(attributes);
        subscribe.setUser(connect.getUser());
        subscribe.setDestination("/user/queue/notifications");
        assertThat(authentication.preSend(MessageBuilder.createMessage(new byte[0], subscribe.getMessageHeaders()), null))
                .isNotNull();

        var registration = new TestChannelRegistration();
        new StompAuthenticationConfiguration(authentication, registry).configureClientOutboundChannel(registration);
        outbound = new ExecutorSubscribableChannel(new SyncTaskExecutor());
        outbound.setInterceptors(registration.interceptors());
        outbound.subscribe(message -> received.incrementAndGet());
    }

    private void deliver() {
        var headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setSessionId("session-1");
        headers.setSubscriptionId("notifications-1");
        headers.setDestination("/queue/notifications-usersession-1");
        // Broker output carries a session ID, not the inbound authenticated attributes.
        outbound.send(MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders()));
    }

    @Test void tokenExpiringAfterSubscribeStopsDeliveryWhileSocketRemainsOpen() {
        deliver();
        assertThat(received).hasValue(1);
        when(clock.instant()).thenReturn(now.plusSeconds(60));
        deliver();
        assertThat(received).hasValue(1);
    }

    @Test void tokenRevokedAfterSubscribeStopsDeliveryBeforeSocketCloseCompletes() {
        deliver();
        when(revocation.isRevoked(eq(user), anyInt())).thenReturn(true);
        deliver();
        assertThat(received).hasValue(1);
        org.mockito.Mockito.verify(revocation, org.mockito.Mockito.atLeastOnce()).isRevoked(user, 3);
    }

    @Test void queuedFrameIsCheckedWhenTheOutboundExecutorDispatchesIt() {
        var pending = new java.util.ArrayList<Runnable>();
        var registration = new TestChannelRegistration();
        new StompAuthenticationConfiguration(authentication, registry).configureClientOutboundChannel(registration);
        outbound = new ExecutorSubscribableChannel(pending::add);
        outbound.setInterceptors(registration.interceptors());
        outbound.subscribe(message -> received.incrementAndGet());
        deliver();
        assertThat(pending).hasSize(1);
        when(clock.instant()).thenReturn(now.plusSeconds(61));
        pending.forEach(Runnable::run);
        assertThat(received).hasValue(0);
    }

    @Test void revokedRegistryEntryCannotDeliverEvenWhenSocketCloseIsDelayed() {
        deliver();
        registry.closeSessions(user);
        deliver();
        assertThat(received).hasValue(1);
    }

    @Test void missingTokenVersionAndUnavailableRevocationChecksFailClosed() {
        attributes.remove(StompAuthenticationInterceptor.TOKEN_VERSION_ATTRIBUTE);
        deliver();
        assertThat(received).hasValue(0);
        attributes.put(StompAuthenticationInterceptor.TOKEN_VERSION_ATTRIBUTE, 3);
        when(revocation.isRevoked(user, 3)).thenThrow(new IllegalStateException("private database details"));
        deliver();
        assertThat(received).hasValue(0);
    }

    @Test void heartbeatDoesNotAuthorizeTheNextNotificationFrame() {
        when(clock.instant()).thenReturn(now.plusSeconds(61));
        var heartbeat = SimpMessageHeaderAccessor.create(SimpMessageType.HEARTBEAT);
        heartbeat.setSessionId("session-1");
        outbound.send(MessageBuilder.createMessage(new byte[0], heartbeat.getMessageHeaders()));
        assertThat(received).hasValue(1);
        deliver();
        assertThat(received).hasValue(1);
    }

    private static class TestChannelRegistration extends ChannelRegistration {
        java.util.List<org.springframework.messaging.support.ChannelInterceptor> interceptors() {
            return getInterceptors();
        }
    }
}
