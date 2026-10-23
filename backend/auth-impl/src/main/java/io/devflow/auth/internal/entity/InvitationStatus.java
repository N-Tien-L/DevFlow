package io.devflow.auth.internal.entity;

/**
 * Lifecycle of a {@link WorkspaceInvitationEntity}. Expiry is not a status: a {@link #PENDING}
 * invitation past its {@code expires_at} is treated as expired.
 */
public enum InvitationStatus {
    PENDING,
    ACCEPTED,
    REVOKED
}
