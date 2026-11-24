package io.devflow.common.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.devflow.common.event.CiFailureLogReadRequestedEvent.Response;
import io.devflow.common.event.CiFailureLogReadRequestedEvent.Status;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CiFailureEventsTest {
    private static final String SHA = "a".repeat(40);
    private static final String DIGEST = "b".repeat(64);

    @Test
    void legacyCiFailureDetectedEventConstructorsRemainSourceCompatibleWithoutProvenance() {
        var fourArg = new CiFailureDetectedEvent("pipe-123", "ci-log:ref", "owner/repo", SHA);
        assertEquals(EventTypes.CI_FAILURE_DETECTED, fourArg.eventType());
        assertEquals("pipe-123", fourArg.pipelineId());
        assertEquals("ci-log:ref", fourArg.rawLogReference());
        assertEquals("owner/repo", fourArg.repo());
        assertEquals(SHA, fourArg.commitSha());
        assertNotNull(fourArg.occurredAt());
        assertNull(fourArg.eventId());
        assertNull(fourArg.repositoryId());
        assertNull(fourArg.workspaceId());
        assertNull(fourArg.providerRunId());
        assertEquals(0, fourArg.runAttempt());

        Instant when = Instant.parse("2026-11-24T01:02:03Z");
        var fiveArg = new CiFailureDetectedEvent("pipe-123", "ci-log:ref", "owner/repo", SHA, when);
        assertEquals(when, fiveArg.occurredAt());
        assertNull(fiveArg.eventId());
        assertNull(fiveArg.workspaceId());
    }

    @Test
    void logReadRequestValidatesBoundsAndFailsClosedWithoutExactlyOneResponse() {
        UUID pipelineId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        String ref = "ci-log:" + UUID.randomUUID();

        assertThrows(IllegalArgumentException.class, () -> CiFailureLogReadRequestedEvent.forWorkspaceMember(
                UUID.randomUUID(), pipelineId, ref, workspaceId, actorId, 0));
        assertThrows(IllegalArgumentException.class, () -> CiFailureLogReadRequestedEvent.forWorkspaceMember(
                UUID.randomUUID(), pipelineId, ref, workspaceId, actorId, -10));
        assertThrows(IllegalArgumentException.class, () -> CiFailureLogReadRequestedEvent.forWorkspaceMember(
                UUID.randomUUID(), pipelineId, ref, workspaceId, actorId, CiFailureLogReadRequestedEvent.MAX_BYTES + 1));
        assertThrows(NullPointerException.class, () -> CiFailureLogReadRequestedEvent.forWorkspaceMember(
                UUID.randomUUID(), pipelineId, ref, workspaceId, null, 1024));

        var zeroResponders = CiFailureLogReadRequestedEvent.forWorkspaceMember(
                UUID.randomUUID(), pipelineId, ref, workspaceId, actorId, 1);
        assertEquals(EventTypes.CI_FAILURE_LOG_READ_REQUESTED, zeroResponders.eventType());
        assertEquals(0, zeroResponders.responseCount());
        assertEquals(Status.DENIED, zeroResponders.response().status());

        var singleResponder = CiFailureLogReadRequestedEvent.forWorkspaceMember(
                UUID.randomUUID(), pipelineId, ref, workspaceId, actorId, CiFailureLogReadRequestedEvent.MAX_BYTES);
        var ready = new Response(Status.READY, "Error: failure context", false, DIGEST);
        singleResponder.respond(ready);
        assertEquals(1, singleResponder.responseCount());
        assertEquals(ready, singleResponder.response());
        assertFalse(ready.toString().contains("failure context"));

        singleResponder.respond(Response.status(Status.NOT_READY));
        assertEquals(2, singleResponder.responseCount());
        assertEquals(Status.DENIED, singleResponder.response().status());
    }

    @Test
    void detectionRequestHandlesLegacyNonUuidPipelineAndResponseEnforcesInvariants() {
        var legacy = new CiFailureDetectedEvent("external-run-42", "ci-log:" + UUID.randomUUID(), "owner/repo", SHA);
        var request = CiFailureLogReadRequestedEvent.forDetection(UUID.randomUUID(), legacy, 1024);
        assertNull(request.pipelineId());
        assertNull(request.actorId());
        assertEquals(legacy, request.detection());
        assertEquals(Status.DENIED, request.response().status());

        assertThrows(IllegalArgumentException.class, () -> new Response(Status.READY, "", false, DIGEST));
        assertThrows(IllegalArgumentException.class, () -> new Response(Status.READY, "Error: x", false, "short"));
        assertThrows(IllegalArgumentException.class, () -> new Response(Status.NOT_READY, "Error: x", false, null));
        assertThrows(IllegalArgumentException.class, () -> new Response(Status.EXPIRED, null, true, null));
        assertThrows(IllegalArgumentException.class, () -> new Response(Status.DENIED, null, false, DIGEST));

        var expired = Response.status(Status.EXPIRED);
        assertEquals(Status.EXPIRED, expired.status());
        assertNull(expired.excerpt());
        assertTrue(expired.toString().contains("EXPIRED"));
    }
}
