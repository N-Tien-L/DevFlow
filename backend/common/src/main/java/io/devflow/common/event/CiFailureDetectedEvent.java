package io.devflow.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Emitted after the sanitized excerpt for a failed attempt has committed. For persisted
 * events, pipelineId is the internal attempt UUID and rawLogReference is an opaque
 * ci-log reference. Consumers deduplicate by eventId. Legacy events have no provenance
 * and cannot authorize a log lookup.
 */
public record CiFailureDetectedEvent(
        String pipelineId, String rawLogReference, String repo, String commitSha, Instant occurredAt,
        UUID eventId, UUID repositoryId, UUID workspaceId, String providerRunId, int runAttempt)
        implements DevFlowEvent {

    public CiFailureDetectedEvent(String pipelineId, String rawLogReference, String repo, String commitSha) {
        this(pipelineId, rawLogReference, repo, commitSha, Instant.now());
    }

    public CiFailureDetectedEvent(String pipelineId, String rawLogReference, String repo, String commitSha, Instant occurredAt) {
        this(pipelineId, rawLogReference, repo, commitSha, occurredAt, null, null, null, null, 0);
    }

    @Override
    public String eventType() {
        return EventTypes.CI_FAILURE_DETECTED;
    }
}
