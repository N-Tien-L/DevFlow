package io.devflow.common.event;

/**
 * Event name constants — the exact strings from the communication contract table in
 * ARCHITECTURE.md Section 4. Publishers/subscribers match on the typed event classes;
 * these names are for logging, serialization, and a future external message broker
 * (ARCHITECTURE.md Section 8).
 */
public final class EventTypes {

    /** Published by Board. Consumed by AI service, Notification. */
    public static final String TASK_CREATED = "task.created";

    /** Published by Board, Git &amp; CI. Consumed by Notification, AI service. */
    public static final String TASK_STATUS_CHANGED = "task.status_changed";

    /** Published by Git &amp; CI. Consumed by Board. */
    public static final String GIT_COMMIT_LINKED = "git.commit_linked";

    /** Published by Git &amp; CI. Consumed by Board, Notification. */
    public static final String GIT_PR_OPENED = "git.pr_opened";

    /** Published by Git &amp; CI. Consumed by Board, Notification. */
    public static final String GIT_PR_MERGED = "git.pr_merged";

    /** Published by Git &amp; CI. Consumed by AI service. */
    public static final String CI_FAILURE_DETECTED = "ci.failure_detected";

    /** Published by AI service. Consumed by Board, Notification. */
    public static final String CI_FAILURE_SUMMARIZED = "ci.failure_summarized";

    /** Published by AI service. Consumed by Notification. */
    public static final String RISK_DEADLINE_FLAGGED = "risk.deadline_flagged";

    private EventTypes() {
    }
}
