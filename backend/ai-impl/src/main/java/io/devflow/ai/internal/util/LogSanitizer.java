package io.devflow.ai.internal.util;

import io.devflow.ai.internal.config.AiProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Sanitizes CI logs by masking credentials, tokens, connection strings and private keys
 * with {@code [REDACTED]}, and truncates oversized logs to an error-focused window (T-031).
 */
@Component
public class LogSanitizer {

    public static final String REDACTED = "[REDACTED]";
    public static final String TRUNCATED_MARKER = "... [truncated] ...\n";

    // 1. Private key blocks (RSA, EC, DSA, OPENSSH)
    private static final Pattern PRIVATE_KEY_PATTERN = Pattern.compile(
            "-----BEGIN\\s+[A-Z0-9_-]*\\s*PRIVATE\\s+KEY-----[\\s\\S]*?-----END\\s+[A-Z0-9_-]*\\s*PRIVATE\\s+KEY-----",
            Pattern.CASE_INSENSITIVE);

    // 2. JWT tokens: eyJ...
    private static final Pattern JWT_PATTERN = Pattern.compile(
            "\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}(?:\\.[A-Za-z0-9_-]+)?\\b");

    // 3. Authorization headers: Authorization: Bearer <token>
    private static final Pattern AUTH_HEADER_PATTERN = Pattern.compile(
            "(?i)\\b(Authorization\\s*:\\s*(?:Bearer|Basic)\\s+)\\S+");

    // 4. URI passwords: postgresql://user:pass@host:5432/db
    private static final Pattern URI_CREDENTIALS_PATTERN = Pattern.compile(
            "(?i)((?:jdbc:[a-z0-9]+|postgresql|postgres|mysql|mongodb|redis)://)([^/\\s:]*):([^\\s]+)@([^@/\\s]+(?::\\d+)?(?:/[^\\s]*)?)");

    // 5. Key-value secrets: password=..., secret=..., api_key: ...
    private static final Pattern KEY_VALUE_SECRET_PATTERN = Pattern.compile(
            "(?i)\\b(password|passwd|pwd|secret|api[_-]?key|access[_-]?token|refresh[_-]?token|auth[_-]?token|private[_-]?key)\\b(\\s*[:=]\\s*)[^\\s,;'\"]+");

    // 6. Well-known API key formats
    private static final Pattern API_KEYS_PATTERN = Pattern.compile(
            "\\b(gh[pousr]_[A-Za-z0-9_]{36,}|glpat-[A-Za-z0-9\\-_]{20,}|xox[baprs]-[0-9]{10,}-[0-9]{10,}-[a-zA-Z0-9]{20,}|sk-[a-zA-Z0-9_-]{20,}|AKIA[0-9A-Z]{16})\\b");

    // Keywords that identify error lines in CI logs
    private static final Pattern ERROR_LINE_PATTERN = Pattern.compile(
            "(?i)\\b(error|fatal|exception|failed|failure|build failed|compilation failure|assertionerror)\\b|^\\s*at\\s+[\\w$.]+\\([\\w$.]+:\\d+\\)");

    private final List<Pattern> configuredPatterns;

    public LogSanitizer(AiProperties properties) {
        Objects.requireNonNull(properties, "properties");
        List<String> rawPatterns = properties.getCiSummary().getSecretPatterns();
        this.configuredPatterns = new ArrayList<>();
        if (rawPatterns != null) {
            for (String regex : rawPatterns) {
                if (StringUtils.hasText(regex)) {
                    try {
                        this.configuredPatterns.add(Pattern.compile(regex));
                    } catch (Exception ignored) {
                        // Ignore malformed regex in config to keep system operational
                    }
                }
            }
        }
    }

    /**
     * Replaces secrets and credentials with {@code [REDACTED]}.
     */
    public String sanitize(String rawLog) {
        if (!StringUtils.hasText(rawLog)) {
            return "";
        }

        String result = rawLog;

        // 1. Private keys
        result = PRIVATE_KEY_PATTERN.matcher(result).replaceAll("[REDACTED_PRIVATE_KEY]");

        // 2. JWTs
        result = JWT_PATTERN.matcher(result).replaceAll("[REDACTED_JWT]");

        // 3. Authorization headers
        result = AUTH_HEADER_PATTERN.matcher(result).replaceAll("$1" + REDACTED);

        // 4. URI passwords
        result = URI_CREDENTIALS_PATTERN.matcher(result).replaceAll("$1$2:" + REDACTED + "@$4");

        // 5. Key-value secrets
        result = KEY_VALUE_SECRET_PATTERN.matcher(result).replaceAll("$1$2" + REDACTED);

        // 6. Well-known API keys
        result = API_KEYS_PATTERN.matcher(result).replaceAll(REDACTED);

        // 7. Configured patterns from application properties
        for (Pattern pattern : configuredPatterns) {
            result = pattern.matcher(result).replaceAll(REDACTED);
        }

        return result;
    }

    /**
     * Truncates log to at most {@code maxSizeKb} kilobytes, preserving the window around errors.
     */
    public String truncateAroundError(String log, int maxSizeKb) {
        if (!StringUtils.hasText(log)) {
            return "";
        }
        int maxChars = Math.max(1024, maxSizeKb * 1024);
        if (log.length() <= maxChars) {
            return log;
        }

        String[] lines = log.split("\r?\n", -1);
        int totalLines = lines.length;

        // Find line indices with error keywords
        List<Integer> errorIndices = new ArrayList<>();
        for (int i = 0; i < totalLines; i++) {
            if (ERROR_LINE_PATTERN.matcher(lines[i]).find()) {
                errorIndices.add(i);
            }
        }

        if (errorIndices.isEmpty()) {
            // No error keyword found: take the last maxChars (tail of CI run)
            int startChar = log.length() - maxChars;
            return TRUNCATED_MARKER + log.substring(startChar);
        }

        // Include context around first and last detected errors
        int firstError = errorIndices.get(0);
        int lastError = errorIndices.get(errorIndices.size() - 1);

        int startLine = Math.max(0, firstError - 20);
        int endLine = Math.min(totalLines - 1, lastError + 20);

        StringBuilder sb = new StringBuilder();
        if (startLine > 0) {
            sb.append(TRUNCATED_MARKER);
        }

        int estimatedLength = 0;
        for (int i = startLine; i <= endLine; i++) {
            estimatedLength += lines[i].length() + 1;
        }

        if (estimatedLength <= maxChars) {
            for (int i = startLine; i <= endLine; i++) {
                sb.append(lines[i]).append('\n');
            }
        } else {
            // Span is larger than maxChars: include head error context, marker, and tail error context
            int halfBudget = Math.max(256, (maxChars - TRUNCATED_MARKER.length() * 2) / 2);
            int current = startLine;
            while (current <= endLine && sb.length() < halfBudget) {
                sb.append(lines[current++]).append('\n');
            }
            sb.append(TRUNCATED_MARKER);

            List<String> tailLines = new ArrayList<>();
            int tailLength = 0;
            int tailIndex = endLine;
            while (tailIndex >= current && tailLength < halfBudget) {
                tailLines.add(lines[tailIndex]);
                tailLength += lines[tailIndex].length() + 1;
                tailIndex--;
            }
            for (int i = tailLines.size() - 1; i >= 0; i--) {
                sb.append(tailLines.get(i)).append('\n');
            }
        }

        if (endLine < totalLines - 1) {
            sb.append(TRUNCATED_MARKER);
        }

        String truncated = sb.toString();
        if (truncated.length() > maxChars) {
            truncated = truncated.substring(0, maxChars) + "\n" + TRUNCATED_MARKER;
        }

        return truncated;
    }
}
