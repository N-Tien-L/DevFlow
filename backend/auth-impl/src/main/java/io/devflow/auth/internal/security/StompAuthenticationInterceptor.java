package io.devflow.auth.internal.security;

import io.devflow.common.security.AuthenticatedSession;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Authenticates CONNECT frames and rechecks session validity before protected STOMP payload delivery. */
@Component
public class StompAuthenticationInterceptor implements ExecutorChannelInterceptor {

    public static final String SESSION_IDENTITY_ATTRIBUTE = AuthenticatedSession.STOMP_SESSION_ATTRIBUTE;
    /** Token version checked on SUBSCRIBE/SEND and again before outbound payload dispatch. */
    static final String TOKEN_VERSION_ATTRIBUTE = StompAuthenticationInterceptor.class.getName() + ".tokenVersion";

    private final JwtTokenProvider tokenProvider;
    private final SessionRevocationService sessionRevocationService;
    private final StompSessionRegistry sessionRegistry;
    private final Clock clock;

    @Autowired
    public StompAuthenticationInterceptor(JwtTokenProvider tokenProvider,
                                          SessionRevocationService sessionRevocationService,
                                          StompSessionRegistry sessionRegistry) {
        this(tokenProvider, sessionRevocationService, sessionRegistry, Clock.systemUTC());
    }

    StompAuthenticationInterceptor(JwtTokenProvider tokenProvider, SessionRevocationService sessionRevocationService,
            StompSessionRegistry sessionRegistry, Clock clock) {
        this.tokenProvider = tokenProvider;
        this.sessionRevocationService = sessionRevocationService;
        this.sessionRegistry = sessionRegistry;
        this.clock = clock;
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
        } else if (accessor.getCommand() == StompCommand.SUBSCRIBE || accessor.getCommand() == StompCommand.SEND) {
            requireSessionNotRevoked(accessor);
        }
        return message;
    }

    /**
     * A socket opened before a password reset must not subscribe or send afterwards (T-008).
     */
    private void requireSessionNotRevoked(StompHeaderAccessor accessor) {
        requireActiveSession(accessor.getSessionAttributes());
    }

    private void requireActiveSession(Map<String, Object> attributes) {
        Object identity = attributes == null ? null : attributes.get(SESSION_IDENTITY_ATTRIBUTE);
        Object version = attributes == null ? null : attributes.get(TOKEN_VERSION_ATTRIBUTE);
        if (!(identity instanceof AuthenticatedSession session) || !(version instanceof Integer tokenVersion)) {
            throw new MessageDeliveryException("AUTH_INVALID");
        }
        if (!session.expiresAt().isAfter(clock.instant())) {
            throw new MessageDeliveryException("AUTH_EXPIRED");
        }
        if (sessionRevocationService.isRevoked(session.userId(), tokenVersion)) {
            throw new MessageDeliveryException("AUTH_REVOKED");
        }
    }

    /** Recheck at executor dispatch time: heartbeats must not extend JWT authorization. */
    @Override
    public Message<?> beforeHandle(Message<?> message, MessageChannel channel,
            org.springframework.messaging.MessageHandler handler) {
        if (SimpMessageHeaderAccessor.getMessageType(message.getHeaders()) != SimpMessageType.MESSAGE) {
            return message;
        }
        try {
            String sessionId = SimpMessageHeaderAccessor.getSessionId(message.getHeaders());
            requireActiveSession(sessionRegistry.authenticatedAttributes(sessionId));
            return message;
        } catch (MessageDeliveryException exception) {
            // Drop without sending another frame through this same outbound channel.
            return null;
        } catch (RuntimeException exception) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("STOMP outbound session check failed exceptionType={}",
                    exception.getClass().getSimpleName());
            return null;
        }
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
            if (!expiresAt.isAfter(clock.instant())) {
                throw new MessageDeliveryException("AUTH_EXPIRED");
            }
            int tokenVersion = JwtTokenProvider.tokenVersion(claims);
            if (sessionRevocationService.isRevoked(userId, tokenVersion)) {
                throw new MessageDeliveryException("AUTH_INVALID");
            }
            UserPrincipal principal = UserPrincipal.create(userId, userId.toString());
            accessor.setUser(UsernamePasswordAuthenticationToken.authenticated(
                    principal, null, principal.getAuthorities()));
            Map<String, Object> attributes = accessor.getSessionAttributes();
            if (attributes == null) {
                throw new MessageDeliveryException("AUTH_INVALID");
            }
            attributes.put(SESSION_IDENTITY_ATTRIBUTE, new AuthenticatedSession(userId, expiresAt));
            attributes.put(TOKEN_VERSION_ATTRIBUTE, tokenVersion);
            sessionRegistry.bind(userId, accessor.getSessionId());
        } catch (MessageDeliveryException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MessageDeliveryException("AUTH_INVALID");
        }
    }
}
