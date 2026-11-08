package io.devflow.gitci.internal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class GitHubWebhookSignatureVerifierTest {
    private static final Instant NOW = Instant.parse("2026-10-16T00:00:00Z");
    private static final String KEY = "It's a Secret to Everybody";
    private static final String SIGNATURE = "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17";
    private final GitHubWebhookSignatureVerifier verifier =
            new GitHubWebhookSignatureVerifier(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void acceptsGithubVectorAndUppercaseHex() {
        byte[] body = "Hello, World!".getBytes(StandardCharsets.UTF_8);
        assertTrue(verifier.verify(body, SIGNATURE, KEY, null, null));
        assertTrue(verifier.verify(body, "sha256=" + SIGNATURE.substring(7).toUpperCase(), KEY, null, null));
    }

    @Test
    void rejectsTamperingMalformedHeadersAndMissingKey() {
        byte[] body = "Hello, World!".getBytes(StandardCharsets.UTF_8);
        assertFalse(verifier.verify("Hello, World?".getBytes(StandardCharsets.UTF_8), SIGNATURE, KEY, null, null));
        assertFalse(verifier.verify(body, null, KEY, null, null));
        assertFalse(verifier.verify(body, "sha1=" + SIGNATURE.substring(7), KEY, null, null));
        assertFalse(verifier.verify(body, SIGNATURE + "," + SIGNATURE, KEY, null, null));
        assertFalse(verifier.verify(body, SIGNATURE, "", null, null));
    }

    @Test
    void acceptsPreviousKeyOnlyBeforeExpiry() {
        byte[] body = "Hello, World!".getBytes(StandardCharsets.UTF_8);
        assertTrue(verifier.verify(body, SIGNATURE, "another secret", KEY, NOW.plusSeconds(1)));
        assertFalse(verifier.verify(body, SIGNATURE, "another secret", KEY, NOW));
        assertFalse(verifier.verify(body, SIGNATURE, "another secret", KEY, NOW.minusSeconds(1)));
    }
}
