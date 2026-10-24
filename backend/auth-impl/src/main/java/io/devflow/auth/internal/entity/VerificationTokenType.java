package io.devflow.auth.internal.entity;

/**
 * Purpose of a {@link VerificationTokenEntity}. Keep in sync with the {@code ck_verification_tokens_type}
 * DB check constraint.
 */
public enum VerificationTokenType {
    EMAIL_VERIFICATION,
    PASSWORD_RESET
}
