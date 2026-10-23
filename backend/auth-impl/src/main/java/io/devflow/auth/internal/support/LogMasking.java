package io.devflow.auth.internal.support;

/**
 * Helpers that keep PII out of log output.
 */
public final class LogMasking {

    private LogMasking() {
    }

    /**
     * Masks the local part of an email address, e.g. {@code tien@gmail.com -> t***@gmail.com}.
     */
    public static String maskEmail(String email) {
        if (email == null) {
            return null;
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
