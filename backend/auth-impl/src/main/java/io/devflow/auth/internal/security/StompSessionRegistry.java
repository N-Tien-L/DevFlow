package io.devflow.auth.internal.security;

import io.devflow.auth.internal.support.LogMasking;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;

/**
 * Tracks the open STOMP WebSocket sessions of each authenticated user on this instance, so a password reset can
 * close them instead of letting an already-open socket keep receiving board updates (T-008).
 *
 * <p>The WebSocket session id is the STOMP {@code simpSessionId}, which {@link StompAuthenticationInterceptor}
 * binds to the user on CONNECT. Sessions on other instances are not reachable from here; they are stopped by the
 * revocation check on SUBSCRIBE / SEND and by the access token's expiry.
 */
@Component
public class StompSessionRegistry implements WebSocketHandlerDecoratorFactory {

    /** Policy-violation close code: the client must authenticate again. */
    static final CloseStatus SESSION_REVOKED = CloseStatus.POLICY_VIOLATION.withReason("Session revoked");

    private static final Logger log = LoggerFactory.getLogger(StompSessionRegistry.class);

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> sessionsByUser = new ConcurrentHashMap<>();

    @Override
    public WebSocketHandler decorate(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                sessions.put(session.getId(), session);
                super.afterConnectionEstablished(session);
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
                forget(session.getId());
                super.afterConnectionClosed(session, closeStatus);
            }
        };
    }

    /**
     * Records that the STOMP session belongs to the user (called on an authenticated CONNECT).
     */
    public void bind(UUID userId, String sessionId) {
        if (sessionId != null) {
            sessionsByUser.computeIfAbsent(userId, id -> ConcurrentHashMap.newKeySet()).add(sessionId);
        }
    }

    /**
     * Closes every open STOMP session of the user on this instance.
     *
     * @return number of sessions closed
     */
    public int closeSessions(UUID userId) {
        Set<String> ids = sessionsByUser.remove(userId);
        if (ids == null) {
            return 0;
        }
        int closed = 0;
        for (String id : ids) {
            WebSocketSession session = sessions.remove(id);
            if (session == null || !session.isOpen()) {
                continue;
            }
            try {
                session.close(SESSION_REVOKED);
                closed++;
            } catch (IOException | RuntimeException e) {
                log.warn("Could not close STOMP session of user {}: {}", userId, LogMasking.describeFailure(e));
            }
        }
        return closed;
    }

    int openSessionCount(UUID userId) {
        Set<String> ids = sessionsByUser.get(userId);
        return ids == null ? 0 : (int) ids.stream().map(sessions::get)
                .filter(session -> session != null && session.isOpen()).count();
    }

    private void forget(String sessionId) {
        sessions.remove(sessionId);
        sessionsByUser.values().forEach(ids -> ids.remove(sessionId));
        sessionsByUser.values().removeIf(Set::isEmpty);
    }
}
