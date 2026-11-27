package io.devflow.gitci.internal;

import java.net.URI;

/** Canonical GitHub URL builder and inline text sanitizer for GitCI read endpoints. */
final class GitHubUrlBuilder {

    private static final String SHA_PATTERN = "^([0-9a-f]{40}|[0-9a-f]{64})$";
    private static final String ALL_ZERO_PATTERN = "^0+$";
    private static final String RUN_ID_PATTERN = "^[1-9][0-9]{0,29}$";
    private static final String REPO_PATH_PATTERN = "^/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)/?$";

    private GitHubUrlBuilder() {
    }

    /**
     * Normalizes a GitHub HTTPS repository URL to {@code https://github.com/<owner>/<repo>},
     * returning {@code null} for any untrusted scheme, host, port, credentials, query, fragment,
     * or traversal segment.
     */
    static String normalizeRepositoryBase(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(url.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || !"github.com".equalsIgnoreCase(uri.getHost())
                    || uri.getPort() != -1
                    || uri.getUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null) {
                return null;
            }
            String rawPath = uri.getRawPath();
            if (rawPath == null || !rawPath.matches(REPO_PATH_PATTERN)) {
                return null;
            }
            String trimmed = rawPath.replaceFirst("/$", "").replaceFirst("\\.git$", "");
            String[] segments = trimmed.substring(1).split("/");
            if (segments.length != 2 || invalidSegment(segments[0]) || invalidSegment(segments[1])) {
                return null;
            }
            return "https://github.com/" + segments[0] + "/" + segments[1];
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    static String commitUrl(String repositoryUrl, String sha) {
        String base = normalizeRepositoryBase(repositoryUrl);
        if (base == null || !isValidSha(sha)) {
            return null;
        }
        return base + "/commit/" + sha;
    }

    static String pullRequestUrl(String repositoryUrl, int prNumber) {
        String base = normalizeRepositoryBase(repositoryUrl);
        if (base == null || prNumber <= 0) {
            return null;
        }
        return base + "/pull/" + prNumber;
    }

    static String ciAttemptUrl(String repositoryUrl, String providerRunId, int runAttempt) {
        String base = normalizeRepositoryBase(repositoryUrl);
        if (base == null || providerRunId == null || !providerRunId.matches(RUN_ID_PATTERN) || runAttempt <= 0) {
            return null;
        }
        return base + "/actions/runs/" + providerRunId + "/attempts/" + runAttempt;
    }

    static String shortSha(String sha) {
        if (sha == null) {
            return "";
        }
        return sha.length() <= 7 ? sha : sha.substring(0, 7);
    }

    static String messagePreview(String rawMessage) {
        if (rawMessage == null || rawMessage.isEmpty()) {
            return "";
        }
        String[] lines = rawMessage.split("\\R");
        for (String line : lines) {
            String cleaned = sanitizeInlineText(line, 140);
            if (!cleaned.isEmpty()) {
                return cleaned;
            }
        }
        return sanitizeInlineText(rawMessage, 140);
    }

    /** Strips ISO control characters and bounds text cleanly by Unicode code points. */
    static String sanitizeInlineText(String raw, int maxCodePoints) {
        if (raw == null || raw.isEmpty() || maxCodePoints <= 0) {
            return "";
        }
        StringBuilder cleaned = new StringBuilder(Math.min(raw.length(), maxCodePoints + 16));
        boolean lastWasSpace = false;
        for (int i = 0; i < raw.length(); ) {
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '\t' || cp == '\n' || cp == '\r') {
                if (cleaned.length() > 0 && !lastWasSpace) {
                    cleaned.append(' ');
                    lastWasSpace = true;
                }
                continue;
            }
            if (Character.isISOControl(cp)) {
                continue;
            }
            if (Character.isWhitespace(cp)) {
                if (cleaned.length() > 0 && !lastWasSpace) {
                    cleaned.append(' ');
                    lastWasSpace = true;
                }
                continue;
            }
            cleaned.appendCodePoint(cp);
            lastWasSpace = false;
        }
        String normalized = cleaned.toString().trim();
        int count = normalized.codePointCount(0, normalized.length());
        if (count <= maxCodePoints) {
            return normalized;
        }
        int endIndex = normalized.offsetByCodePoints(0, maxCodePoints);
        return normalized.substring(0, endIndex).trim() + "…";
    }

    private static boolean isValidSha(String sha) {
        return sha != null && sha.matches(SHA_PATTERN) && !sha.matches(ALL_ZERO_PATTERN);
    }

    private static boolean invalidSegment(String segment) {
        return segment.isEmpty() || ".".equals(segment) || "..".equals(segment);
    }
}
