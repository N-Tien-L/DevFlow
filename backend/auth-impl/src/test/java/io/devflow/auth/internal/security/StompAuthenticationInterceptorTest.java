package io.devflow.auth.internal.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.devflow.common.security.AuthenticatedSession;
import io.jsonwebtoken.Claims;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.HashMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.core.Authentication;

class StompAuthenticationInterceptorTest {

    private final JwtTokenProvider tokenProvider = mock(JwtTokenProvider.class);
    private final SessionRevocationService sessionRevocationService = mock(SessionRevocationService.class);
    private final StompSessionRegistry sessionRegistry = new StompSessionRegistry();
    private final StompAuthenticationInterceptor interceptor =
            new StompAuthenticationInterceptor(tokenProvider, sessionRevocationService, sessionRegistry);

    @Test
    void rejectsSubscribeAndSendOnASessionRevokedAfterConnect() {
        UUID userId = UUID.randomUUID();
        mockAccessToken(userId, Instant.now().plusSeconds(60));
        StompHeaderAccessor connect = connectAccessor("valid-token");
        interceptor.preSend(message(connect), null);

        org.mockito.Mockito.when(sessionRevocationService.isRevoked(
                org.mockito.ArgumentMatchers.eq(userId), org.mockito.ArgumentMatchers.anyInt())).thenReturn(true);
        for (StompCommand command : new StompCommand[] {StompCommand.SUBSCRIBE, StompCommand.SEND}) {
            StompHeaderAccessor frame = StompHeaderAccessor.create(command);
            frame.setSessionId("session-1");
            frame.setSessionAttributes(connect.getSessionAttributes());
            frame.setUser(connect.getUser());
            frame.setDestination("/topic/boards/" + UUID.randomUUID());
            frame.setLeaveMutable(true);

            assertThatThrownBy(() -> interceptor.preSend(message(frame), null))
                    .isInstanceOf(MessageDeliveryException.class)
                    .hasMessageContaining("AUTH_REVOKED");
        }
    }

    @Test
    void connectRegistersTheSocketSoAResetCanCloseIt() throws Exception {
        UUID userId = UUID.randomUUID();
        org.springframework.web.socket.WebSocketSession socket =
                mock(org.springframework.web.socket.WebSocketSession.class);
        when(socket.getId()).thenReturn("session-1");
        when(socket.isOpen()).thenReturn(true);
        sessionRegistry.decorate(mock(org.springframework.web.socket.WebSocketHandler.class))
                .afterConnectionEstablished(socket);
        mockAccessToken(userId, Instant.now().plusSeconds(60));
        StompHeaderAccessor connect = connectAccessor("valid-token");
        interceptor.preSend(message(connect), null);

        // Still valid: SUBSCRIBE goes through.
        StompHeaderAccessor subscribe = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        subscribe.setSessionId("session-1");
        subscribe.setSessionAttributes(connect.getSessionAttributes());
        subscribe.setUser(connect.getUser());
        subscribe.setDestination("/topic/boards/" + UUID.randomUUID());
        subscribe.setLeaveMutable(true);
        assertThat(interceptor.preSend(message(subscribe), null)).isNotNull();

        assertThat(sessionRegistry.closeSessions(userId)).isEqualTo(1);
        org.mockito.Mockito.verify(socket).close(StompSessionRegistry.SESSION_REVOKED);
    }

    @Test
    void rejectsAnAccessTokenRevokedByAPasswordChange() {
        UUID userId = UUID.randomUUID();
        mockAccessToken(userId, Instant.now().plusSeconds(60));
        org.mockito.Mockito.when(sessionRevocationService.isRevoked(
                org.mockito.ArgumentMatchers.eq(userId), org.mockito.ArgumentMatchers.anyInt())).thenReturn(true);
        StompHeaderAccessor accessor = connectAccessor("valid-token");

        assertThatThrownBy(() -> interceptor.preSend(message(accessor), null))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("AUTH_INVALID");
    }

    @Test
    void bindsTheVerifiedAccessTokenIdentityAndExpiryToTheSession() {
        UUID userId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plusSeconds(60);
        mockAccessToken(userId, expiresAt);
        StompHeaderAccessor accessor = connectAccessor("valid-token");

        Message<?> authenticatedMessage = interceptor.preSend(message(accessor), null);

        StompHeaderAccessor authenticatedAccessor = MessageHeaderAccessor.getAccessor(
                authenticatedMessage, StompHeaderAccessor.class);
        assertThat(authenticatedAccessor.getUser()).isInstanceOf(Authentication.class);
        Authentication authentication = (Authentication) authenticatedAccessor.getUser();
        assertThat(((UserPrincipal) authentication.getPrincipal()).userId()).isEqualTo(userId);
        assertThat(authenticatedAccessor.getSessionAttributes().get(AuthenticatedSession.STOMP_SESSION_ATTRIBUTE))
                .isEqualTo(new AuthenticatedSession(userId, expiresAt.truncatedTo(ChronoUnit.MILLIS)));
    }

    @Test
    void rejectsRefreshTokensExpiredTokensAndDuplicateAuthorizationHeaders() {
        UUID userId = UUID.randomUUID();
        mockAccessToken(userId, Instant.now().plusSeconds(60));
        when(tokenProvider.validateToken("refresh-token")).thenReturn(true);
        StompHeaderAccessor refresh = connectAccessor("refresh-token");
        Claims refreshClaims = claims(userId, Instant.now().plusSeconds(60), "refresh");
        when(tokenProvider.getClaims("refresh-token")).thenReturn(refreshClaims);

        assertThatThrownBy(() -> interceptor.preSend(message(refresh), null))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessage("AUTH_INVALID");

        StompHeaderAccessor expired = connectAccessor("expired-token");
        when(tokenProvider.validateToken("expired-token")).thenReturn(true);
        Claims expiredClaims = claims(userId, Instant.now().minusSeconds(1), "access");
        when(tokenProvider.getClaims("expired-token")).thenReturn(expiredClaims);
        assertThatThrownBy(() -> interceptor.preSend(message(expired), null))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessage("AUTH_EXPIRED");

        StompHeaderAccessor duplicate = connectAccessor("valid-token");
        duplicate.addNativeHeader("Authorization", "Bearer another-token");
        assertThatThrownBy(() -> interceptor.preSend(message(duplicate), null))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessage("AUTH_REQUIRED");
    }

    private void mockAccessToken(UUID userId, Instant expiresAt) {
        Claims accessClaims = claims(userId, expiresAt, "access");
        when(tokenProvider.validateToken("valid-token")).thenReturn(true);
        when(tokenProvider.getIssuer()).thenReturn("devflow");
        when(tokenProvider.getClaims("valid-token")).thenReturn(accessClaims);
    }

    private static Claims claims(UUID userId, Instant expiresAt, String type) {
        Claims claims = mock(Claims.class);
        when(claims.get("type", String.class)).thenReturn(type);
        when(claims.getIssuer()).thenReturn("devflow");
        when(claims.getSubject()).thenReturn(userId.toString());
        when(claims.getExpiration()).thenReturn(Date.from(expiresAt));
        return claims;
    }

    private static StompHeaderAccessor connectAccessor(String token) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionId("session-1");
        accessor.setSessionAttributes(new HashMap<>());
        accessor.addNativeHeader("Authorization", "Bearer " + token);
        accessor.setLeaveMutable(true);
        return accessor;
    }

    private static Message<byte[]> message(StompHeaderAccessor accessor) {
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
