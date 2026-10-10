package io.devflow.board.internal;

import io.devflow.board.api.BoardMutationMessage;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/** Sends small, board-scoped invalidation messages to the shared STOMP broker. */
@Component
public class BoardRealtimeBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(BoardRealtimeBroadcaster.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;
    private final int sendTimeoutMillis;

    public BoardRealtimeBroadcaster(
            SimpMessagingTemplate messagingTemplate,
            ObjectProvider<MeterRegistry> meterRegistryProvider,
            @Value("${devflow.websocket.board.send-timeout-ms:100}") int sendTimeoutMillis) {
        if (sendTimeoutMillis < 1) {
            throw new IllegalArgumentException("Board realtime send timeout must be positive");
        }
        this.messagingTemplate = messagingTemplate;
        this.meterRegistryProvider = meterRegistryProvider;
        this.sendTimeoutMillis = sendTimeoutMillis;
        this.messagingTemplate.setSendTimeout(sendTimeoutMillis);
    }

    public void broadcast(BoardMutationMessage message) {
        long startedAt = System.nanoTime();
        String outcome = "success";
        try {
            messagingTemplate.convertAndSend("/topic/boards/" + message.boardId(), message);
            log.info("Board realtime publication type={} eventId={} boardId={} correlationId={} actorId={}",
                    message.type(), message.eventId(), message.boardId(), message.correlationId(), message.actorId());
        } catch (RuntimeException exception) {
            outcome = "failure";
            log.error("Board realtime send failed type={} eventId={} boardId={} correlationId={} exceptionType={}",
                    message.type(), message.eventId(), message.boardId(), message.correlationId(),
                    exception.getClass().getSimpleName());
            throw exception;
        } finally {
            recordMetrics(message, outcome, System.nanoTime() - startedAt);
        }
    }

    private void recordMetrics(BoardMutationMessage message, String outcome, long elapsedNanos) {
        try {
            MeterRegistry registry = meterRegistryProvider.getIfAvailable();
            if (registry == null) {
                return;
            }
            registry.counter("devflow.board.realtime.publications",
                    "type", message.type().name(), "outcome", outcome).increment();
            Timer.builder("devflow.board.realtime.publication.duration")
                    .tag("type", message.type().name())
                    .tag("outcome", outcome)
                    .register(registry)
                    .record(elapsedNanos, TimeUnit.NANOSECONDS);
        } catch (RuntimeException exception) {
            log.warn("Board realtime metric failed type={} exceptionType={}",
                    message.type(), exception.getClass().getSimpleName());
        }
    }
}
