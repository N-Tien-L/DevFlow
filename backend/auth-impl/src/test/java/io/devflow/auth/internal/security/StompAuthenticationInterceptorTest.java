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
    private final StompAuthenticationInterceptor interceptor = new StompAuthenticationInterceptor(tokenProvider);

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
