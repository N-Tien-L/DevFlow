package io.devflow.gitci.internal;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Verifies the signature over the exact bytes received, without parsing the payload. */
final class GitHubWebhookSignatureVerifier {
    private final Clock clock;

    GitHubWebhookSignatureVerifier(Clock clock) {
        this.clock = clock;
    }

    boolean verify(byte[] body, String signature, String current, String previous, Instant previousValidUntil) {
        if (body == null || signature == null || !signature.matches("sha256=[0-9a-fA-F]{64}")
                || current == null || current.isEmpty()) {
            return false;
        }
        byte[] supplied = HexFormat.of().parseHex(signature.substring(7));
        boolean matches = MessageDigest.isEqual(sign(body, current), supplied);
        if (previous != null && !previous.isEmpty() && previousValidUntil != null
                && clock.instant().isBefore(previousValidUntil)) {
            // Always calculate both eligible MACs, even when the current key already matched.
            matches |= MessageDigest.isEqual(sign(body, previous), supplied);
        }
        return matches;
    }

    private byte[] sign(byte[] body, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(body);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC SHA-256 unavailable", ex);
        }
    }
}
