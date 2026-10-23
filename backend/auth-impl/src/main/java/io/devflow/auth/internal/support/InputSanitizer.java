package io.devflow.auth.internal.support;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Server-side normalisation of user-supplied text before it is persisted.
 */
public final class InputSanitizer {

    private static final Pattern HTML_TAG = Pattern.compile("<[^>]*>");
    private static final Pattern ANGLE_BRACKETS = Pattern.compile("[<>]");
    private static final Pattern CONTROL_CHARS = Pattern.compile("\\p{Cntrl}");
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    private InputSanitizer() {
    }

    /**
     * Trims and lower-cases an email address so lookups and uniqueness are case-insensitive.
     */
    public static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Strips HTML markup and control characters from a display name and collapses whitespace.
     */
    public static String sanitizeDisplayName(String name) {
        if (name == null) {
            return null;
        }
        String withoutTags = HTML_TAG.matcher(name).replaceAll("");
        String withoutBrackets = ANGLE_BRACKETS.matcher(withoutTags).replaceAll("");
        String withoutControls = CONTROL_CHARS.matcher(withoutBrackets).replaceAll(" ");
        return WHITESPACE_RUN.matcher(withoutControls).replaceAll(" ").trim();
    }
}
