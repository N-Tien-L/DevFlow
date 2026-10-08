package io.devflow.board.internal;

import io.devflow.common.event.TaskCreatedEvent;
import io.devflow.common.event.DevFlowEvent;
import io.devflow.common.event.TaskStatusChangedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Publishes immutable task event snapshots only after their owning transaction commits. */
@Component
public class TaskEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(TaskEventPublisher.class);

    private final ApplicationEventPublisher eventPublisher;
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    public TaskEventPublisher(
            ApplicationEventPublisher eventPublisher,
            ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.eventPublisher = eventPublisher;
        this.meterRegistryProvider = meterRegistryProvider;
    }

    public void publishCreated(
            UUID taskId,
            UUID boardId,
            UUID workspaceId,
            UUID columnId,
            String description,
            UUID actorId) {
        requireTransactionSynchronization();
        TaskCreatedEvent event = new TaskCreatedEvent(
                taskId,
                workspaceId,
                description,
                boardId,
                columnId,
                UUID.randomUUID(),
                BoardRequestContext.correlationId(),
                actorId,
                java.time.Instant.now());
        registerAfterCommit(event);
    }

    public void publishStatusChanged(
            UUID taskId,
            UUID boardId,
            UUID workspaceId,
            UUID sourceColumnId,
            UUID destinationColumnId,
            String oldStatus,
            String newStatus,
            String oldStatusCategory,
            String newStatusCategory,
            TaskStatusChangedEvent.StatusChangeCause cause,
            UUID actorId) {
        requireTransactionSynchronization();
        TaskStatusChangedEvent event = new TaskStatusChangedEvent(
                taskId,
                oldStatus,
                newStatus,
                cause,
                boardId,
                workspaceId,
                sourceColumnId,
                destinationColumnId,
                oldStatusCategory,
                newStatusCategory,
                UUID.randomUUID(),
                BoardRequestContext.correlationId(),
                actorId,
                java.time.Instant.now());
        registerAfterCommit(event);
    }

    private static void requireTransactionSynchronization() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Task events must be registered inside an active transaction");
        }
    }

    private void registerAfterCommit(DevFlowEvent event) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                dispatch(event);
            }
        });
    }

    private void dispatch(DevFlowEvent event) {
        String eventType = event.eventType();
        long startedAt = System.nanoTime();
        String outcome = "success";
        try {
            eventPublisher.publishEvent(event);
            if (event instanceof TaskCreatedEvent created) {
                log.info("Dispatched task event type={} eventId={} taskId={} boardId={} workspaceId={} "
                                + "columnId={} correlationId={} actorId={}",
                        eventType, created.eventId(), created.taskId(), created.boardId(), created.projectId(),
                        created.columnId(), created.correlationId(), created.actorId());
            } else if (event instanceof TaskStatusChangedEvent changed) {
                log.info("Dispatched task event type={} eventId={} taskId={} boardId={} workspaceId={} "
                                + "sourceColumnId={} destinationColumnId={} cause={} correlationId={} actorId={}",
                        eventType, changed.eventId(), changed.taskId(), changed.boardId(), changed.workspaceId(),
                        changed.sourceColumnId(), changed.destinationColumnId(), changed.cause(),
                        changed.correlationId(), changed.actorId());
            }
        } catch (RuntimeException exception) {
            outcome = "failure";
            UUID eventId = event instanceof TaskCreatedEvent created ? created.eventId()
                    : ((TaskStatusChangedEvent) event).eventId();
            UUID taskId = event instanceof TaskCreatedEvent created ? created.taskId()
                    : ((TaskStatusChangedEvent) event).taskId();
            log.error("Task event dispatch failed type={} eventId={} taskId={} exceptionType={}",
                    eventType, eventId, taskId, exception.getClass().getSimpleName());
        } finally {
            recordDispatchMetrics(eventType, outcome, System.nanoTime() - startedAt);
        }
    }

    private void recordDispatchMetrics(String eventType, String outcome, long elapsedNanos) {
        try {
            MeterRegistry registry = meterRegistryProvider.getIfAvailable();
            if (registry == null) {
                return;
            }
            registry.counter("devflow.task.events.dispatch", "eventType", eventType, "outcome", outcome)
                    .increment();
            Timer.builder("devflow.task.events.dispatch.duration")
                    .tag("eventType", eventType)
                    .tag("outcome", outcome)
                    .register(registry)
                    .record(elapsedNanos, TimeUnit.NANOSECONDS);
        } catch (RuntimeException exception) {
            log.warn("Task event dispatch metric failed type={} exceptionType={}",
                    eventType, exception.getClass().getSimpleName());
        }
    }
}
