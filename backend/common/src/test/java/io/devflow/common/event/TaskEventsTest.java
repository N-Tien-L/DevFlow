package io.devflow.common.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@SuppressWarnings("deprecation")
class TaskEventsTest {

    @Test
    void keepsLegacyTaskCreatedConstructorsAndWireContract() {
        UUID taskId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-10-08T00:00:00Z");

        TaskCreatedEvent event = new TaskCreatedEvent(taskId, workspaceId, "description", occurredAt);

        assertInstanceOf(DevFlowEvent.class, event);
        assertEquals(taskId, event.taskId());
        assertEquals(workspaceId, event.projectId());
        assertEquals("description", event.description());
        assertEquals(EventTypes.TASK_CREATED, event.eventType());
        assertEquals(occurredAt, event.occurredAt());
        assertNull(event.boardId());
    }

    @Test
    void rejectsIncompleteNonLegacyCreatedEvent() {
        NullPointerException exception = assertThrows(NullPointerException.class, () -> new TaskCreatedEvent(
                UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), null, UUID.randomUUID(), Instant.now()));
        assertEquals("correlationId", exception.getMessage());
    }

    @Test
    void keepsLegacyStatusConstructorAndWireContract() {
        UUID taskId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-10-08T00:00:00Z");

        TaskStatusChangedEvent event = new TaskStatusChangedEvent(
                taskId, "To Do", "In Progress", TaskStatusChangedEvent.StatusChangeCause.MANUAL, occurredAt);

        assertInstanceOf(DevFlowEvent.class, event);
        assertEquals(EventTypes.TASK_STATUS_CHANGED, event.eventType());
        assertEquals("To Do", event.oldStatus());
        assertEquals("In Progress", event.newStatus());
        assertEquals(TaskStatusChangedEvent.StatusChangeCause.MANUAL, event.cause());
        assertEquals(occurredAt, event.occurredAt());
        assertNull(event.sourceColumnId());
    }

    @Test
    void rejectsIncompleteAndSameColumnStatusEvents() {
        UUID boardId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID columnId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        Instant occurredAt = Instant.now();

        NullPointerException exception = assertThrows(NullPointerException.class, () -> new TaskStatusChangedEvent(
                UUID.randomUUID(), "A", "B", TaskStatusChangedEvent.StatusChangeCause.MANUAL,
                boardId, workspaceId, columnId, UUID.randomUUID(), "TODO", "DONE",
                eventId, null, actorId, occurredAt));
        assertEquals("correlationId", exception.getMessage());

        IllegalArgumentException sameColumnException = assertThrows(IllegalArgumentException.class,
                () -> new TaskStatusChangedEvent(
                UUID.randomUUID(), "A", "B", TaskStatusChangedEvent.StatusChangeCause.MANUAL,
                boardId, workspaceId, columnId, columnId, "TODO", "DONE",
                eventId, correlationId, actorId, occurredAt));
        assertTrue(sameColumnException.getMessage().contains("different columns"));

        IllegalArgumentException invalidCategoryException = assertThrows(IllegalArgumentException.class,
                () -> new TaskStatusChangedEvent(
                        UUID.randomUUID(), "A", "B", TaskStatusChangedEvent.StatusChangeCause.MANUAL,
                        boardId, workspaceId, columnId, UUID.randomUUID(), "NOT_A_CATEGORY", "DONE",
                        eventId, correlationId, actorId, occurredAt));
        assertTrue(invalidCategoryException.getMessage().contains("status catalog"));
    }

    @Test
    void permitsSystemGitEventWithoutActorButRequiresActorForManualEvent() {
        UUID boardId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID sourceColumnId = UUID.randomUUID();
        UUID destinationColumnId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        Instant occurredAt = Instant.now();

        TaskStatusChangedEvent gitEvent = new TaskStatusChangedEvent(
                UUID.randomUUID(), "To Do", "In Progress", TaskStatusChangedEvent.StatusChangeCause.GIT,
                boardId, workspaceId, sourceColumnId, destinationColumnId, "TODO", "IN_PROGRESS",
                eventId, correlationId, null, occurredAt);

        assertNull(gitEvent.actorId());
        NullPointerException exception = assertThrows(NullPointerException.class, () -> new TaskStatusChangedEvent(
                UUID.randomUUID(), "To Do", "In Progress", TaskStatusChangedEvent.StatusChangeCause.MANUAL,
                boardId, workspaceId, sourceColumnId, destinationColumnId, "TODO", "IN_PROGRESS",
                eventId, correlationId, null, occurredAt));
        assertEquals("actorId", exception.getMessage());
    }
}
