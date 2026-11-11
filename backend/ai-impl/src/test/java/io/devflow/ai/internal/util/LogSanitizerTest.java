package io.devflow.ai.internal.util;

import static org.assertj.core.api.Assertions.assertThat;

import io.devflow.ai.internal.config.AiProperties;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("LogSanitizer Unit Tests")
class LogSanitizerTest {

    private AiProperties properties;
    private LogSanitizer sanitizer;

    @BeforeEach
    void setUp() {
        properties = new AiProperties();
        sanitizer = new LogSanitizer(properties);
    }

    @Test
    void redactsJwtToken() {
        String log = "Error authenticating request: eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9."
                + "eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ."
                + "SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c failed validation";

        String sanitized = sanitizer.sanitize(log);

        assertThat(sanitized).doesNotContain("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9");
        assertThat(sanitized).contains("[REDACTED_JWT]");
    }

    @Test
    void redactsPrivateKeyBlock() {
        String log = """
                [DEBUG] Loading credentials:
                -----BEGIN RSA PRIVATE KEY-----
                MIIEowIBAAKCAQEA0Y1234567890abcdefghijklmnopqrstuvwxyz
                MIIEowIBAAKCAQEA0Y1234567890abcdefghijklmnopqrstuvwxyz
                -----END RSA PRIVATE KEY-----
                Key loaded successfully.
                """;

        String sanitized = sanitizer.sanitize(log);

        assertThat(sanitized).doesNotContain("MIIEowIBAAKCAQEA0Y1234567890abcdefghijklmnopqrstuvwxyz");
        assertThat(sanitized).contains("[REDACTED_PRIVATE_KEY]");
        assertThat(sanitized).contains("Key loaded successfully.");
    }

    @Test
    void redactsDatabaseUriCredentials() {
        String log = "Connecting to postgresql://devflow_user:SuperSecretP@ss123@db.prod.internal:5432/devflow_db";

        String sanitized = sanitizer.sanitize(log);

        assertThat(sanitized).doesNotContain("SuperSecretP@ss123");
        assertThat(sanitized).contains("postgresql://devflow_user:[REDACTED]@db.prod.internal:5432/devflow_db");
    }

    @Test
    void redactsAuthorizationHeader() {
        String log = """
                > GET /api/v1/protected HTTP/1.1
                > Authorization: Bearer secret-token-xyz-123456789
                > User-Agent: curl/7.68.0
                """;

        String sanitized = sanitizer.sanitize(log);

        assertThat(sanitized).doesNotContain("secret-token-xyz-123456789");
        assertThat(sanitized).contains("Authorization: Bearer [REDACTED]");
    }

    @Test
    void redactsKeyValueSecrets() {
        String log = """
                config:
                  password=mySuperSecretPassword123
                  api_key: sk-proj-1234567890abcdef
                  secret: top-secret-token
                  normal_property: public-value
                """;

        String sanitized = sanitizer.sanitize(log);

        assertThat(sanitized).doesNotContain("mySuperSecretPassword123");
        assertThat(sanitized).doesNotContain("sk-proj-1234567890abcdef");
        assertThat(sanitized).doesNotContain("top-secret-token");
        assertThat(sanitized).contains("password=[REDACTED]");
        assertThat(sanitized).contains("api_key: [REDACTED]");
        assertThat(sanitized).contains("secret: [REDACTED]");
        assertThat(sanitized).contains("normal_property: public-value");
    }

    @Test
    void redactsStandardApiTokens() {
        String log = "Github token: ghp_123456789012345678901234567890123456, AWS: AKIAIOSFODNN7EXAMPLE";

        String sanitized = sanitizer.sanitize(log);

        assertThat(sanitized).doesNotContain("ghp_123456789012345678901234567890123456");
        assertThat(sanitized).doesNotContain("AKIAIOSFODNN7EXAMPLE");
        assertThat(sanitized).contains("[REDACTED]");
    }

    @Test
    void appliesConfiguredSecretPatterns() {
        properties.getCiSummary().setSecretPatterns(List.of("CUSTOM_SECRET_[A-Z0-9]+"));
        LogSanitizer customSanitizer = new LogSanitizer(properties);

        String log = "Found custom secret: CUSTOM_SECRET_XYZ999 in log output";
        String sanitized = customSanitizer.sanitize(log);

        assertThat(sanitized).doesNotContain("CUSTOM_SECRET_XYZ999");
        assertThat(sanitized).contains("[REDACTED]");
    }

    @Test
    void truncateAroundErrorPreservesErrorLinesWhenLogExceedsCap() {
        StringBuilder largeLog = new StringBuilder();
        for (int i = 1; i <= 2000; i++) {
            largeLog.append("Line ").append(i).append(": Normal compilation message\n");
        }
        largeLog.append("Line 2001: Compilation error: package com.example.missing does not exist\n");
        largeLog.append("Line 2002: \tat com.example.MyService.execute(MyService.java:42)\n");
        for (int i = 2003; i <= 2100; i++) {
            largeLog.append("Line ").append(i).append(": BUILD FAILED in 45s\n");
        }

        // Limit to 4 KB
        String truncated = sanitizer.truncateAroundError(largeLog.toString(), 4);

        assertThat(truncated.length()).isLessThanOrEqualTo(4 * 1024 + 100);
        assertThat(truncated).contains("Compilation error: package com.example.missing does not exist");
        assertThat(truncated).contains("BUILD FAILED");
        assertThat(truncated).contains(LogSanitizer.TRUNCATED_MARKER);
    }

    @Test
    void truncateReturnsOriginalLogIfUnderCap() {
        String shortLog = "Line 1\nLine 2: Compilation error in App.java\nLine 3: BUILD FAILED";

        String truncated = sanitizer.truncateAroundError(shortLog, 32);

        assertThat(truncated).isEqualTo(shortLog);
    }

    @Test
    void truncateRetainsTailIfNoErrorKeywordsFound() {
        StringBuilder largeLog = new StringBuilder();
        for (int i = 1; i <= 2000; i++) {
            largeLog.append("Line ").append(i).append(": Process running info\n");
        }
        largeLog.append("Line 2001: Process terminated with exit status 137");

        String truncated = sanitizer.truncateAroundError(largeLog.toString(), 2);

        assertThat(truncated).contains("Line 2001: Process terminated with exit status 137");
        assertThat(truncated).contains(LogSanitizer.TRUNCATED_MARKER);
    }
}
