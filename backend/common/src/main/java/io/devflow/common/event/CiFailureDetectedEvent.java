package io.devflow.common.event;

import java.time.Instant;

/**
 * {@code ci.failure_detected} — published by Git &amp; CI when a pipeline run fails.
 * Consumed by AI service, which summarizes the raw log into a likely cause
 * (PRODUCT_SPEC.md Section 5.2a, ARCHITECTURE.md Section 4).
 *
 * @param pipelineId      id of the failed pipeline/run
 * @param rawLogReference reference to the full raw log (e.g. storage key or URL) — not the
 *                        log itself, to keep event payloads small
 * @param repo            repository whose pipeline failed
 * @param commitSha       commit the failed pipeline ran against
 */
public record CiFailureDetectedEvent(
        String pipelineId, String rawLogReference, String repo, String commitSha, Instant occurredAt)
        implements DevFlowEvent {

    public CiFailureDetectedEvent(String pipelineId, String rawLogReference, String repo, String commitSha) {
        this(pipelineId, rawLogReference, repo, commitSha, Instant.now());
    }

    @Override
    public String eventType() {
        return EventTypes.CI_FAILURE_DETECTED;
    }
}
