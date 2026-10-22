package io.devflow.board.internal;

import io.devflow.board.api.BoardMutationData;
import io.devflow.board.api.BoardMutationMessage;
import io.devflow.board.api.BoardMutationType;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Captures a mutation snapshot and publishes it only after its owning transaction commits. */
@Component
public class BoardRealtimePublisher {

    private static final Logger log = LoggerFactory.getLogger(BoardRealtimePublisher.class);

    private final BoardRealtimeBroadcaster broadcaster;

    public BoardRealtimePublisher(BoardRealtimeBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    public void publish(UUID boardId, BoardMutationType type, BoardMutationData data, UUID actorId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Board realtime messages must be registered inside an active transaction");
        }

        BoardMutationMessage message = new BoardMutationMessage(
                BoardMutationMessage.CURRENT_SCHEMA_VERSION,
                UUID.randomUUID(),
                type,
                boardId,
                Instant.now(),
                BoardRequestContext.correlationId(),
                actorId,
                data);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    broadcaster.broadcast(message);
                } catch (RuntimeException exception) {
                    log.error("Board realtime publication failed type={} eventId={} boardId={} correlationId={} "
                                    + "exceptionType={}",
                            message.type(), message.eventId(), message.boardId(), message.correlationId(),
                            exception.getClass().getSimpleName());
                }
            }
        });
    }
}
