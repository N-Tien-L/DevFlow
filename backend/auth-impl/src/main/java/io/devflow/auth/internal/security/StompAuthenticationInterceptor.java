package io.devflow.auth.internal.security;

import io.devflow.common.security.AuthenticatedSession;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Authenticates browser STOMP CONNECT frames using an access token sent in a STOMP header. */
@Component
public class StompAuthenticationInterceptor implements ChannelInterceptor {

    public static final String SESSION_IDENTITY_ATTRIBUTE = AuthenticatedSession.STOMP_SESSION_ATTRIBUTE;

    private final JwtTokenProvider tokenProvider;

    public StompAuthenticationInterceptor(JwtTokenProvider tokenProvider) {
        this.tokenProvider = tokenProvider;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }
        if (accessor.getCommand() == StompCommand.CONNECT || accessor.getCommand() == StompCommand.STOMP) {
            if (accessor.getUser() != null) {
                throw new MessageDeliveryException("AUTH_INVALID");
            }
            authenticateConnect(accessor);
        } else if (accessor.getCommand() != null && accessor.getUser() == null) {
            throw new MessageDeliveryException("AUTH_REQUIRED");
        }
        return message;
    }

    private void authenticateConnect(StompHeaderAccessor accessor) {
        List<String> authorizationHeaders = accessor.getNativeHeader("Authorization");
        if (authorizationHeaders == null || authorizationHeaders.size() != 1) {
            throw new MessageDeliveryException("AUTH_REQUIRED");
        }
        String header = authorizationHeaders.get(0);
        if (!StringUtils.hasText(header) || !header.startsWith("Bearer ")) {
            throw new MessageDeliveryException("AUTH_INVALID");
        }

        String token = header.substring("Bearer ".length()).trim();
        try {
            if (!tokenProvider.validateToken(token)) {
                throw new MessageDeliveryException("AUTH_INVALID");
            }
            var claims = tokenProvider.getClaims(token);
            if (!"access".equals(claims.get("type", String.class))
                    || !StringUtils.hasText(claims.getIssuer())
                    || !claims.getIssuer().equals(tokenProvider.getIssuer())) {
                throw new MessageDeliveryException("AUTH_INVALID");
            }

            UUID userId = UUID.fromString(claims.getSubject());
            Instant expiresAt = claims.getExpiration().toInstant();
            if (!expiresAt.isAfter(Instant.now())) {
                throw new MessageDeliveryException("AUTH_EXPIRED");
            }
            UserPrincipal principal = UserPrincipal.create(userId, userId.toString());
            accessor.setUser(UsernamePasswordAuthenticationToken.authenticated(
                    principal, null, principal.getAuthorities()));
            Map<String, Object> attributes = accessor.getSessionAttributes();
            if (attributes == null) {
                throw new MessageDeliveryException("AUTH_INVALID");
            }
            attributes.put(SESSION_IDENTITY_ATTRIBUTE, new AuthenticatedSession(userId, expiresAt));
        } catch (MessageDeliveryException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MessageDeliveryException("AUTH_INVALID");
        }
    }
}
