package io.devflow.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code user.account_deleted} - published by Auth when a user erases their account (GDPR right to erasure,
 * T-009). Consumed by Board (unassign tasks, anonymise comment/audit authors) - ARCHITECTURE.md Section 4.
 * Notification rows go with the user through a foreign key cascade; AI service will subscribe once it stores
 * per-user conversation history.
 *
 * <p>Carries the user id only, never PII. Delivered after the Auth transaction commits; consumers must be
 * idempotent because the in-process bus has no delivery guarantee or de-duplication.
 *
 * @param userId id of the deleted account; consumers replace references with {@link DeletedUser#ID}
 */
public record UserAccountDeletedEvent(UUID userId, Instant occurredAt) implements DevFlowEvent {

    public UserAccountDeletedEvent(UUID userId) {
        this(userId, Instant.now());
    }

    @Override
    public String eventType() {
        return EventTypes.USER_ACCOUNT_DELETED;
    }
}
