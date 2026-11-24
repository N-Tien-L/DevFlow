package io.devflow.common.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Synchronous internal request. Publish and use response() on the same thread. */
public final class CiFailureLogReadRequestedEvent implements DevFlowEvent {
    public static final int MAX_BYTES = 16_384;

    public enum Status { READY, NOT_READY, NOT_FOUND, DENIED, EXPIRED }

    public record Response(Status status, String excerpt, boolean truncated, String digest) {
        public Response {
            Objects.requireNonNull(status);
            if (status == Status.READY) {
                if (excerpt == null || excerpt.isBlank() || digest == null || !digest.matches("[0-9a-f]{64}"))
                    throw new IllegalArgumentException("READY requires an excerpt and digest");
            } else if (excerpt != null || truncated || digest != null) {
                throw new IllegalArgumentException("Only READY can carry an excerpt");
            }
        }

        public static Response status(Status status) { return new Response(status, null, false, null); }
        @Override public String toString() { return "Response[status=" + status + "]"; }
    }

    private final UUID correlationId;
    private final UUID pipelineId;
    private final String reference;
    private final UUID workspaceId;
    private final UUID actorId;
    private final CiFailureDetectedEvent detection;
    private final int maxBytes;
    private final Instant occurredAt = Instant.now();
    private Response response;
    private int responseCount;

    private CiFailureLogReadRequestedEvent(UUID correlationId, UUID pipelineId, String reference, UUID workspaceId,
                                            UUID actorId, CiFailureDetectedEvent detection, int maxBytes) {
        this.correlationId = Objects.requireNonNull(correlationId);
        this.pipelineId = pipelineId;
        this.reference = reference;
        this.workspaceId = workspaceId;
        this.actorId = actorId;
        this.detection = detection;
        if (maxBytes < 1 || maxBytes > MAX_BYTES) throw new IllegalArgumentException("Invalid CI log byte limit");
        this.maxBytes = maxBytes;
    }

    public static CiFailureLogReadRequestedEvent forDetection(UUID correlationId, CiFailureDetectedEvent detection, int maxBytes) {
        Objects.requireNonNull(detection);
        UUID pipeline = null;
        try { pipeline = UUID.fromString(detection.pipelineId()); }
        catch (IllegalArgumentException | NullPointerException ignored) { /* Legacy event fails closed. */ }
        return new CiFailureLogReadRequestedEvent(correlationId, pipeline,
                detection.rawLogReference(), detection.workspaceId(), null, detection, maxBytes);
    }

    public static CiFailureLogReadRequestedEvent forWorkspaceMember(UUID correlationId, UUID pipelineId, String reference,
                                                                     UUID workspaceId, UUID actorId, int maxBytes) {
        return new CiFailureLogReadRequestedEvent(correlationId, Objects.requireNonNull(pipelineId), Objects.requireNonNull(reference),
                Objects.requireNonNull(workspaceId), Objects.requireNonNull(actorId), null, maxBytes);
    }

    public UUID correlationId() { return correlationId; }
    public UUID pipelineId() { return pipelineId; }
    public String reference() { return reference; }
    public UUID workspaceId() { return workspaceId; }
    public UUID actorId() { return actorId; }
    public CiFailureDetectedEvent detection() { return detection; }
    public int maxBytes() { return maxBytes; }
    public synchronized int responseCount() { return responseCount; }

    public synchronized void respond(Response result) {
        Objects.requireNonNull(result);
        response = ++responseCount == 1 ? result : null;
    }

    public synchronized Response response() {
        return responseCount == 1 ? response : Response.status(Status.DENIED);
    }
    @Override public String eventType() { return EventTypes.CI_FAILURE_LOG_READ_REQUESTED; }
    @Override public Instant occurredAt() { return occurredAt; }
}
