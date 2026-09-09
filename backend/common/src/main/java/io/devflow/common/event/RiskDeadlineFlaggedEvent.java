package io.devflow.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code risk.deadline_flagged} — published by AI service when actual completion pace vs.
 * plan indicates a deadline is at risk (PRODUCT_SPEC.md Section 5.2b, proactive deadline
 * risk alerts). Consumed by Notification (ARCHITECTURE.md Section 4).
 *
 * @param taskId   at-risk task; {@code null} when the flag concerns a whole sprint
 * @param sprintId at-risk sprint; {@code null} when the flag concerns a single task
 * @param reason   human-readable explanation of why the deadline is at risk
 * @param severity how urgent the risk is
 */
public record RiskDeadlineFlaggedEvent(
        UUID taskId, UUID sprintId, String reason, Severity severity, Instant occurredAt)
        implements DevFlowEvent {

    public RiskDeadlineFlaggedEvent(UUID taskId, UUID sprintId, String reason, Severity severity) {
        this(taskId, sprintId, reason, severity, Instant.now());
    }

    @Override
    public String eventType() {
        return EventTypes.RISK_DEADLINE_FLAGGED;
    }

    public enum Severity {
        LOW,
        MEDIUM,
        HIGH
    }
}
