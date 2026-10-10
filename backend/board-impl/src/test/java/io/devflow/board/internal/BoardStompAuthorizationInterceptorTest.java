package io.devflow.board.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.devflow.board.internal.repository.BoardRepository;
import io.devflow.common.security.AuthenticatedSession;
import java.time.Instant;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

class BoardStompAuthorizationInterceptorTest {

    private final BoardRepository boardRepository = mock(BoardRepository.class);
    private final WorkspaceMembershipPermissionService permissionService =
            mock(WorkspaceMembershipPermissionService.class);
    private final BoardSubscriptionRegistry subscriptionRegistry = new BoardSubscriptionRegistry();
    @SuppressWarnings("unchecked")
    private final ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meterRegistryProvider = mock(ObjectProvider.class);
    private final BoardStompAuthorizationInterceptor interceptor = new BoardStompAuthorizationInterceptor(
            boardRepository, permissionService, subscriptionRegistry, meterRegistryProvider);

    @Test
    void registersOnlyAWorkspaceMemberOnAnExactCanonicalBoardTopic() {
        UUID userId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID boardId = UUID.randomUUID();
        when(boardRepository.findWorkspaceIdByBoardId(boardId)).thenReturn(Optional.of(workspaceId));
        when(permissionService.isMember(userId, workspaceId)).thenReturn(true);

        Message<byte[]> message = subscribeMessage("session-1", "subscription-1", boardId.toString(),
                new AuthenticatedSession(userId, Instant.now().plusSeconds(60)));
        Message<?> result = interceptor.preSend(message, null);

        assertThat(result).isSameAs(message);
        assertThat(subscriptionRegistry.activeSessionCount()).isEqualTo(1);
        assertThat(subscriptionRegistry.activeSubscriptionCount()).isEqualTo(1);
        assertThat(subscriptionRegistry.find("session-1", "subscription-1").boardId()).isEqualTo(boardId);
    }

    @Test
    void rejectsAnOutsiderWithoutRegisteringTheSubscription() {
        UUID userId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID boardId = UUID.randomUUID();
        when(boardRepository.findWorkspaceIdByBoardId(boardId)).thenReturn(Optional.of(workspaceId));
        when(permissionService.isMember(userId, workspaceId)).thenReturn(false);

        assertThatThrownBy(() -> interceptor.preSend(subscribeMessage("session-1", "sub-1", boardId.toString(),
                new AuthenticatedSession(userId, Instant.now().plusSeconds(60))), null))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessage("ACCESS_DENIED");
        assertThat(subscriptionRegistry.activeSubscriptionCount()).isZero();
    }

    @Test
    void rejectsWildcardsMalformedDestinationsAndExpiredSessions() {
        UUID userId = UUID.randomUUID();
        AuthenticatedSession valid = new AuthenticatedSession(userId, Instant.now().plusSeconds(60));
        AuthenticatedSession expired = new AuthenticatedSession(userId, Instant.now().minusSeconds(1));

        assertThatThrownBy(() -> interceptor.preSend(subscribeMessage("session-1", "sub-1", "*", valid), null))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessage("INVALID_DESTINATION");
        assertThatThrownBy(() -> interceptor.preSend(subscribeMessage("session-1", "sub-1", "../secret", valid), null))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessage("INVALID_DESTINATION");
        assertThatThrownBy(() -> interceptor.preSend(subscribeMessage("session-1", "sub-1",
                UUID.randomUUID().toString(), expired), null))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessage("AUTH_REQUIRED");
        assertThat(subscriptionRegistry.activeSubscriptionCount()).isZero();
    }

    private static Message<byte[]> subscribeMessage(
            String sessionId, String subscriptionId, String destination, AuthenticatedSession identity) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setSessionId(sessionId);
        accessor.setSubscriptionId(subscriptionId);
        accessor.setDestination("/topic/boards/" + destination);
        accessor.setSessionAttributes(new HashMap<>());
        accessor.getSessionAttributes().put(AuthenticatedSession.STOMP_SESSION_ATTRIBUTE, identity);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
