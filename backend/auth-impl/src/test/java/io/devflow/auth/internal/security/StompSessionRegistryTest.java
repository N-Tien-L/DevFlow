package io.devflow.auth.internal.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

class StompSessionRegistryTest {

    private final StompSessionRegistry registry = new StompSessionRegistry();
    private final WebSocketHandler handler = registry.decorate(mock(WebSocketHandler.class));

    @Test
    void closesOnlyTheOpenSessionsOfTheGivenUser() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();
        WebSocketSession first = openSession("ws-1");
        WebSocketSession second = openSession("ws-2");
        WebSocketSession other = openSession("ws-3");
        registry.bind(userId, "ws-1");
        registry.bind(userId, "ws-2");
        registry.bind(otherUserId, "ws-3");

        assertThat(registry.closeSessions(userId)).isEqualTo(2);

        verify(first).close(StompSessionRegistry.SESSION_REVOKED);
        verify(second).close(StompSessionRegistry.SESSION_REVOKED);
        verify(other, never()).close(any(CloseStatus.class));
        assertThat(registry.openSessionCount(userId)).isZero();
        assertThat(registry.openSessionCount(otherUserId)).isEqualTo(1);
        assertThat(registry.closeSessions(userId)).isZero();
    }

    @Test
    void forgetsSessionsTheClientAlreadyClosed() throws Exception {
        UUID userId = UUID.randomUUID();
        WebSocketSession session = openSession("ws-1");
        registry.bind(userId, "ws-1");

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        assertThat(registry.openSessionCount(userId)).isZero();
        assertThat(registry.closeSessions(userId)).isZero();
        verify(session, never()).close(any(CloseStatus.class));
    }

    private WebSocketSession openSession(String id) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        handler.afterConnectionEstablished(session);
        return session;
    }
}
