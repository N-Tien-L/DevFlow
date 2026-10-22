package io.devflow.board.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.devflow.common.event.TaskCreatedEvent;
import io.devflow.common.event.TaskStatusChangedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class TaskEventPublisherTest {

    private final ApplicationEventPublisher eventBus = mock(ApplicationEventPublisher.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<MeterRegistry> meterRegistryProvider = mock(ObjectProvider.class);
    private final TaskEventPublisher publisher = new TaskEventPublisher(eventBus, meterRegistryProvider);

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void publishesAnImmutableCreatedSnapshotOnlyWhenAfterCommitRuns() {
        startTransactionSynchronization();
        UUID taskId = UUID.randomUUID();
        UUID boardId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID columnId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        publisher.publishCreated(taskId, boardId, workspaceId, columnId, "sanitized", actorId);

        verify(eventBus, never()).publishEvent(any(Object.class));
        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

        var captor = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(eventBus).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(TaskCreatedEvent.class);
        TaskCreatedEvent event = (TaskCreatedEvent) captor.getValue();
        assertThat(event.taskId()).isEqualTo(taskId);
        assertThat(event.boardId()).isEqualTo(boardId);
        assertThat(event.projectId()).isEqualTo(workspaceId);
        assertThat(event.columnId()).isEqualTo(columnId);
        assertThat(event.actorId()).isEqualTo(actorId);
        assertThat(event.description()).isEqualTo("sanitized");
        assertThat(event.eventId()).isNotNull();
        assertThat(event.correlationId()).isNotNull();
        assertThat(event.occurredAt()).isNotNull();
    }

    @Test
    void doesNotPublishWhenTheTransactionRollsBack() {
        startTransactionSynchronization();

        publisher.publishCreated(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), null, UUID.randomUUID());

        // A rollback does not invoke TransactionSynchronization.afterCommit().
        verifyNoInteractions(eventBus);
    }

    @Test
    void keepsSystemGitCauseOnTheCommittedStatusEventWithoutInventingAnActor() {
        startTransactionSynchronization();
        UUID taskId = UUID.randomUUID();
        UUID boardId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID sourceColumnId = UUID.randomUUID();
        UUID destinationColumnId = UUID.randomUUID();

        publisher.publishStatusChanged(
                taskId, boardId, workspaceId, sourceColumnId, destinationColumnId,
                "To Do", "In Progress", "TODO", "IN_PROGRESS",
                TaskStatusChangedEvent.StatusChangeCause.GIT, null);
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

        var captor = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(eventBus).publishEvent(captor.capture());
        TaskStatusChangedEvent event = (TaskStatusChangedEvent) captor.getValue();
        assertThat(event.taskId()).isEqualTo(taskId);
        assertThat(event.boardId()).isEqualTo(boardId);
        assertThat(event.workspaceId()).isEqualTo(workspaceId);
        assertThat(event.sourceColumnId()).isEqualTo(sourceColumnId);
        assertThat(event.destinationColumnId()).isEqualTo(destinationColumnId);
        assertThat(event.cause()).isEqualTo(TaskStatusChangedEvent.StatusChangeCause.GIT);
        assertThat(event.actorId()).isNull();
    }

    @Test
    void failsClosedWhenThereIsNoActiveTransaction() {
        assertThatThrownBy(() -> publisher.publishCreated(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active transaction");

        verifyNoInteractions(eventBus);
    }

    @Test
    void doesNotThrowAListenerFailureBackIntoTheCommittedRequest() {
        startTransactionSynchronization();
        when(meterRegistryProvider.getIfAvailable()).thenReturn(null);
        org.mockito.Mockito.doThrow(new IllegalStateException("secret database detail"))
                .when(eventBus).publishEvent(any(Object.class));

        publisher.publishCreated(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), null, UUID.randomUUID());
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

        verify(eventBus).publishEvent(any(Object.class));
    }

    @Test
    void doesNotLetMetricsFailureEscapeAnAfterCommitCallback() {
        startTransactionSynchronization();
        when(meterRegistryProvider.getIfAvailable())
                .thenThrow(new IllegalStateException("metrics backend unavailable"));

        publisher.publishCreated(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), null, UUID.randomUUID());
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

        verify(eventBus).publishEvent(any(Object.class));
    }

    private static void startTransactionSynchronization() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }
}
