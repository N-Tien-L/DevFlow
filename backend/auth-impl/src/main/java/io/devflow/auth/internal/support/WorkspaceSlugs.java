package io.devflow.auth.internal.support;

import java.text.Normalizer;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * Derives URL-safe workspace slugs from display names, e.g. {@code "Dự án Đồ án" -> "du-an-do-an-k3x9qa"}.
 */
public final class WorkspaceSlugs {

    static final int MAX_BASE_LENGTH = 48;
    static final int SUFFIX_LENGTH = 6;

    private static final String FALLBACK = "workspace";
    private static final String SUFFIX_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]+");
    private static final Pattern EDGE_DASHES = Pattern.compile("^-+|-+$");

    private WorkspaceSlugs() {
    }

    /**
     * Lower-case ASCII base: diacritics removed (Vietnamese {@code đ} mapped to {@code d}), every other run of
     * non-alphanumerics collapsed to a dash, capped at {@value #MAX_BASE_LENGTH} chars. Falls back to
     * {@code workspace} when nothing usable remains (e.g. a name in a non-Latin script).
     */
    public static String base(String name) {
        String ascii = Normalizer.normalize(name.replace('đ', 'd').replace('Đ', 'D'), Normalizer.Form.NFD);
        ascii = COMBINING_MARKS.matcher(ascii).replaceAll("").toLowerCase(Locale.ROOT);
        String slug = EDGE_DASHES.matcher(NON_ALPHANUMERIC.matcher(ascii).replaceAll("-")).replaceAll("");
        if (slug.length() > MAX_BASE_LENGTH) {
            slug = EDGE_DASHES.matcher(slug.substring(0, MAX_BASE_LENGTH)).replaceAll("");
        }
        return slug.isEmpty() ? FALLBACK : slug;
    }

    /**
     * Base slug plus a random suffix, so two workspaces with the same name still get distinct slugs.
     */
    public static String generate(String name) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        StringBuilder slug = new StringBuilder(base(name)).append('-');
        for (int i = 0; i < SUFFIX_LENGTH; i++) {
            slug.append(SUFFIX_ALPHABET.charAt(random.nextInt(SUFFIX_ALPHABET.length())));
        }
        return slug.toString();
    }
}
