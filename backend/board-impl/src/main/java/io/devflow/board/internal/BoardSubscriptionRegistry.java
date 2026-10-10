package io.devflow.board.internal;

import io.devflow.common.security.AuthenticatedSession;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/** Tracks server-authorized topic subscriptions by the trusted STOMP session identifiers. */
@Component
public class BoardSubscriptionRegistry {

    private final ConcurrentMap<String, SessionSubscriptions> sessions = new ConcurrentHashMap<>();

    public synchronized boolean register(String sessionId, AuthenticatedSession identity, String subscriptionId,
            BoardSubscription subscription) {
        if (activeSubscriptionCount() >= 200
                || countSubscriptionsForUser(identity.userId()) >= 25
                || countSubscriptionsForSession(sessionId) >= 10) {
            return false;
        }
        SessionSubscriptions current = sessions.computeIfAbsent(sessionId, key -> new SessionSubscriptions(identity));
        if (!current.identity.equals(identity)) {
            return false;
        }
        return current.subscriptions.putIfAbsent(subscriptionId, subscription) == null;
    }

    public BoardSubscription find(String sessionId, String subscriptionId) {
        if (sessionId == null || subscriptionId == null) {
            return null;
        }
        SessionSubscriptions session = sessions.get(sessionId);
        return session == null ? null : session.subscriptions.get(subscriptionId);
    }

    public AuthenticatedSession identityForSession(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        SessionSubscriptions session = sessions.get(sessionId);
        return session == null ? null : session.identity;
    }

    public void unregister(String sessionId, String subscriptionId) {
        if (sessionId == null || subscriptionId == null) {
            return;
        }
        SessionSubscriptions session = sessions.get(sessionId);
        if (session != null) {
            session.subscriptions.remove(subscriptionId);
            if (session.subscriptions.isEmpty()) {
                sessions.remove(sessionId, session);
            }
        }
    }

    public boolean isAuthorized(String sessionId, AuthenticatedSession identity,
            String subscriptionId, java.util.UUID boardId) {
        if (sessionId == null || identity == null || !identity.expiresAt().isAfter(java.time.Instant.now())) {
            return false;
        }
        SessionSubscriptions session = sessions.get(sessionId);
        if (session == null || !session.identity.equals(identity)) {
            return false;
        }
        BoardSubscription subscription = session.subscriptions.get(subscriptionId);
        return subscription != null && subscription.boardId().equals(boardId);
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        sessions.remove(event.getSessionId());
    }

    public void onDisconnect(StompHeaderAccessor accessor) {
        String sessionId = accessor.getSessionId();
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }

    public int activeSessionCount() {
        return sessions.size();
    }

    public int activeSubscriptionCount() {
        return sessions.values().stream().mapToInt(session -> session.subscriptions.size()).sum();
    }

    public int countSubscriptionsForSession(String sessionId) {
        SessionSubscriptions session = sessions.get(sessionId);
        return session == null ? 0 : session.subscriptions.size();
    }

    public int countSubscriptionsForUser(java.util.UUID userId) {
        return sessions.values().stream()
                .filter(session -> session.identity.userId().equals(userId))
                .mapToInt(session -> session.subscriptions.size())
                .sum();
    }

    private static final class SessionSubscriptions {
        private final AuthenticatedSession identity;
        private final ConcurrentMap<String, BoardSubscription> subscriptions = new ConcurrentHashMap<>();

        private SessionSubscriptions(AuthenticatedSession identity) {
            this.identity = identity;
        }
    }
}
