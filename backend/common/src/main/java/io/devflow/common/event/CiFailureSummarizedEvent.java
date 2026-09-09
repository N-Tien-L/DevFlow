package io.devflow.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code ci.failure_summarized} — published by AI service after summarizing a CI failure.
 * Consumed by Board (attach summary to the related task) and Notification
 * (PRODUCT_SPEC.md Section 5.2a, ARCHITECTURE.md Section 4).
 *
 * @param pipelineId    id of the failed pipeline/run this summary belongs to
 * @param summary       short, human-readable summary of the likely cause
 * @param relatedTaskId task linked to the failing code change; {@code null} when the
 *                      failure could not be resolved to a task yet
 */
public record CiFailureSummarizedEvent(
        String pipelineId, String summary, UUID relatedTaskId, Instant occurredAt)
        implements DevFlowEvent {

    public CiFailureSummarizedEvent(String pipelineId, String summary, UUID relatedTaskId) {
        this(pipelineId, summary, relatedTaskId, Instant.now());
    }

    @Override
    public String eventType() {
        return EventTypes.CI_FAILURE_SUMMARIZED;
    }
}
