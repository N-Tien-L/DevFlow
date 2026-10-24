package io.devflow.auth.internal.pat;

import io.devflow.auth.internal.verification.VerificationTokenGenerator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Format of personal access tokens: {@code dfp_<12 hex lookup key><43 char base64url secret>} (59 chars).
 *
 * <p>The first 16 chars ({@code dfp_} + lookup key) are the public {@code token_prefix}: they locate the row
 * and identify the token in listings and secret scanners. The whole token is hashed with SHA-256 for storage,
 * and the stored digest is compared in constant time.
 */
@Component
public class PersonalAccessTokenCodec {

    public static final String PREFIX = "dfp_";
    static final int LOOKUP_KEY_BYTES = 6;
    static final int PUBLIC_PREFIX_LENGTH = PREFIX.length() + LOOKUP_KEY_BYTES * 2;
    static final int TOKEN_LENGTH = PUBLIC_PREFIX_LENGTH + 43;

    private static final Pattern FORMAT = Pattern.compile("^dfp_[0-9a-f]{12}[A-Za-z0-9_-]{43}$");

    private final VerificationTokenGenerator secretGenerator;
    private final SecureRandom secureRandom = new SecureRandom();

    public PersonalAccessTokenCodec(VerificationTokenGenerator secretGenerator) {
        this.secretGenerator = secretGenerator;
    }

    /**
     * Generates a new token. The raw value must be shown to the owner once and never stored.
     */
    public GeneratedToken generate() {
        byte[] lookupKey = new byte[LOOKUP_KEY_BYTES];
        secureRandom.nextBytes(lookupKey);
        String publicPrefix = PREFIX + HexFormat.of().formatHex(lookupKey);
        String raw = publicPrefix + secretGenerator.generateRawToken();
        return new GeneratedToken(raw, publicPrefix, hash(raw));
    }

    /**
     * Returns the public prefix of a well-formed token, or empty for anything else (no DB lookup needed).
     */
    public Optional<String> publicPrefix(String raw) {
        if (raw == null || raw.length() != TOKEN_LENGTH || !FORMAT.matcher(raw).matches()) {
            return Optional.empty();
        }
        return Optional.of(raw.substring(0, PUBLIC_PREFIX_LENGTH));
    }

    public String hash(String raw) {
        return secretGenerator.hash(raw);
    }

    /**
     * Constant-time comparison of a presented token against a stored hex digest.
     */
    public boolean matches(String raw, String storedHash) {
        return MessageDigest.isEqual(
                hash(raw).getBytes(StandardCharsets.US_ASCII), storedHash.getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * A freshly generated token. {@link #toString()} hides the raw value.
     */
    public record GeneratedToken(String raw, String publicPrefix, String hash) {

        @Override
        public String toString() {
            return "GeneratedToken[publicPrefix=" + publicPrefix + "]";
        }
    }
}
