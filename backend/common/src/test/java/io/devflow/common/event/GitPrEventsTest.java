package io.devflow.common.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GitPrEventsTest {
    @Test void oldConstructorsRetainTimestampAndUnknownActorSemantics() {
        UUID task = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-10-24T12:00:00Z");
        var opened = new GitPrOpenedEvent(task, "https://example.test/pr/1", "repo", occurredAt);
        var merged = new GitPrMergedEvent(task, "https://example.test/pr/1", "repo", occurredAt);
        assertNull(opened.actorId());
        assertNull(merged.actorId());
        assertEquals(occurredAt, opened.occurredAt());
        assertEquals(occurredAt, merged.occurredAt());
        assertEquals(EventTypes.GIT_PR_OPENED, opened.eventType());
        assertEquals(EventTypes.GIT_PR_MERGED, merged.eventType());
        assertNull(new GitPrOpenedEvent(task, "url", "repo").actorId());
        assertNull(new GitPrMergedEvent(task, "url", "repo").actorId());
        assertNotNull(new GitPrOpenedEvent(task, "url", "repo").occurredAt());
        assertNotNull(new GitPrMergedEvent(task, "url", "repo").occurredAt());
    }

    @Test void canonicalConstructorsCarryTheDevFlowActor() {
        UUID task = UUID.randomUUID(), actor = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-10-24T12:00:00Z");
        assertEquals(actor, new GitPrOpenedEvent(task, "url", "repo", actor, occurredAt).actorId());
        assertEquals(actor, new GitPrMergedEvent(task, "url", "repo", actor, occurredAt).actorId());
    }
}
