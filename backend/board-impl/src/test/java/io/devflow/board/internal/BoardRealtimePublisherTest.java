package io.devflow.board.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.devflow.board.api.BoardMutationData;
import io.devflow.board.api.BoardMutationMessage;
import io.devflow.board.api.BoardMutationType;
import io.devflow.board.api.BoardRefreshTarget;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class BoardRealtimePublisherTest {

    private final SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<MeterRegistry> meterRegistryProvider = mock(ObjectProvider.class);
    private final BoardRealtimeBroadcaster broadcaster =
            new BoardRealtimeBroadcaster(messagingTemplate, meterRegistryProvider, 100);
    private final BoardRealtimePublisher publisher = new BoardRealtimePublisher(broadcaster);

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void broadcastsAnImmutableBoardScopedSnapshotOnlyAfterCommit() {
        startTransactionSynchronization();
        UUID boardId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID columnId = UUID.randomUUID();
        var affectedColumns = new java.util.ArrayList<>(List.of(columnId));
        BoardMutationData data = new BoardMutationData(taskId, columnId, null, null,
                null, 0, affectedColumns, List.of("title"), List.of(BoardRefreshTarget.TASK_LISTS));

        publisher.publish(boardId, BoardMutationType.CARD_CREATED, data, UUID.randomUUID());
        affectedColumns.clear();

        verify(messagingTemplate, never()).convertAndSend(any(String.class), any(Object.class));
        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

        var messageCaptor = org.mockito.ArgumentCaptor.forClass(BoardMutationMessage.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/boards/" + boardId), messageCaptor.capture());
        assertThat(messageCaptor.getValue().type()).isEqualTo(BoardMutationType.CARD_CREATED);
        assertThat(messageCaptor.getValue().boardId()).isEqualTo(boardId);
        assertThat(messageCaptor.getValue().eventId()).isNotNull();
        assertThat(messageCaptor.getValue().correlationId()).isNotNull();
        assertThat(messageCaptor.getValue().data().affectedColumnIds()).containsExactly(columnId);
    }

    @Test
    void doesNotBroadcastWhenTheTransactionRollsBack() {
        startTransactionSynchronization();
        publisher.publish(UUID.randomUUID(), BoardMutationType.BOARD_DELETED,
                new BoardMutationData(null, null, null, null, null, null, List.of(),
                        List.of("deleted"), List.of(BoardRefreshTarget.BOARD)), UUID.randomUUID());

        verify(messagingTemplate, never()).convertAndSend(any(String.class), any(Object.class));
    }

    @Test
    void requiresAnActiveTransactionBeforeRegisteringAPublication() {
        assertThatThrownBy(() -> publisher.publish(UUID.randomUUID(), BoardMutationType.CARD_DELETED,
                new BoardMutationData(null, null, null, null, null, null, List.of(), List.of(), List.of()),
                UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active transaction");

        verify(messagingTemplate, never()).convertAndSend(any(String.class), any(Object.class));
    }

    private static void startTransactionSynchronization() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }
}
