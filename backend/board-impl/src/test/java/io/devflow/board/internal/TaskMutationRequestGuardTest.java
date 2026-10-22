package io.devflow.board.internal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TaskMutationRequestGuardTest {

    private final UUID actorId = UUID.randomUUID();
    private AuthenticatedActorProvider actorProvider;

    @BeforeEach
    void setUp() {
        actorProvider = mock(AuthenticatedActorProvider.class);
        when(actorProvider.requireUserId()).thenReturn(actorId);
    }

    @Test
    void requiresAnAuthenticatedActor() {
        TaskMutationRequestGuard guard = new TaskMutationRequestGuard(actorProvider, new BoardRateLimiter(60, 60));

        guard.authorizeMutation();

        verify(actorProvider).requireUserId();
    }

    @Test
    void rejectsActorOnceTheMutationQuotaIsExhausted() {
        TaskMutationRequestGuard guard = new TaskMutationRequestGuard(actorProvider, new BoardRateLimiter(2, 60));

        guard.authorizeMutation();
        guard.authorizeMutation();

        assertThatThrownBy(guard::authorizeMutation)
                .isInstanceOf(BoardApiException.class)
                .extracting(exception -> ((BoardApiException) exception).code())
                .isEqualTo("RATE_LIMIT_EXCEEDED");
    }
}
