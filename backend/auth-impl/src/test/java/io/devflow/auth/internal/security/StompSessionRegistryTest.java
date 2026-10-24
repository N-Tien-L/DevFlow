package io.devflow.auth.internal.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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

    @Test
    void concurrentBindAndDisconnectOfTheSameUserNeverLoseASession() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 2_000; round++) {
                UUID userId = UUID.randomUUID();
                // The user's only bound socket disconnects while a second one finishes CONNECT.
                WebSocketSession leaving = openSession("leaving-" + round);
                registry.bind(userId, "leaving-" + round);
                WebSocketSession joining = openSession("joining-" + round);
                String joiningId = "joining-" + round;
                CountDownLatch start = new CountDownLatch(1);

                Future<?> bind = pool.submit(() -> {
                    start.await();
                    registry.bind(userId, joiningId);
                    return null;
                });
                Future<?> disconnect = pool.submit(() -> {
                    start.await();
                    handler.afterConnectionClosed(leaving, CloseStatus.NORMAL);
                    return null;
                });
                start.countDown();
                bind.get(5, TimeUnit.SECONDS);
                disconnect.get(5, TimeUnit.SECONDS);

                assertThat(registry.openSessionCount(userId)).as("round %d", round).isEqualTo(1);
                assertThat(registry.closeSessions(userId)).as("round %d", round).isEqualTo(1);
                verify(joining).close(StompSessionRegistry.SESSION_REVOKED);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void socketClosedBeforeBindIsNotTracked() throws Exception {
        UUID userId = UUID.randomUUID();
        WebSocketSession session = openSession("ws-1");
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        registry.bind(userId, "ws-1");

        assertThat(registry.closeSessions(userId)).isZero();
    }

    private WebSocketSession openSession(String id) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        handler.afterConnectionEstablished(session);
        return session;
    }
}
