package io.devflow.auth.internal.entity;

/**
 * Account lifecycle state. Only {@link #ACTIVE} accounts may obtain JWT tokens.
 */
public enum UserStatus {
    PENDING_VERIFICATION,
    ACTIVE
}
