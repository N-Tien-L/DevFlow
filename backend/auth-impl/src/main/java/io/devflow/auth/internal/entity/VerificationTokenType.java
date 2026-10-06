package io.devflow.auth.internal.entity;

/**
 * Purpose of a {@link VerificationTokenEntity}. Extend (together with the DB check constraint)
 * when password reset is introduced.
 */
public enum VerificationTokenType {
    EMAIL_VERIFICATION
}
