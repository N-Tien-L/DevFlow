package io.devflow.auth.internal.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JwtTokenProvider Tests")
class JwtTokenProviderTest {

    private JwtProperties jwtProperties;
    private JwtTokenProvider jwtTokenProvider;

    private final UUID testUserId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final String testEmail = "developer@devflow.io";

    @BeforeEach
    void setUp() {
        jwtProperties = new JwtProperties();
        jwtProperties.setSecret("404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970");
        jwtProperties.setAccessTokenExpirationMs(3600000L); // 1 hour
        jwtProperties.setRefreshTokenExpirationMs(604800000L); // 7 days
        jwtProperties.setIssuer("devflow-test");

        jwtTokenProvider = new JwtTokenProvider(jwtProperties);
    }

    @Test
    @DisplayName("Should generate valid access token with correct claims")
    void generateAccessToken_shouldProduceValidTokenWithCorrectClaims() {
        String token = jwtTokenProvider.generateAccessToken(testUserId, testEmail);

        assertThat(token).isNotBlank();
        assertThat(jwtTokenProvider.validateToken(token)).isTrue();
        assertThat(jwtTokenProvider.getUserIdFromToken(token)).isEqualTo(testUserId);
        assertThat(jwtTokenProvider.getEmailFromToken(token)).isEqualTo(testEmail);
        assertThat(jwtTokenProvider.getTokenTypeFromToken(token)).isEqualTo("access");
    }

    @Test
    @DisplayName("Should generate valid refresh token with refresh claim type")
    void generateRefreshToken_shouldProduceValidRefreshToken() {
        String token = jwtTokenProvider.generateRefreshToken(testUserId, testEmail);

        assertThat(token).isNotBlank();
        assertThat(jwtTokenProvider.validateToken(token)).isTrue();
        assertThat(jwtTokenProvider.getUserIdFromToken(token)).isEqualTo(testUserId);
        assertThat(jwtTokenProvider.getEmailFromToken(token)).isEqualTo(testEmail);
        assertThat(jwtTokenProvider.getTokenTypeFromToken(token)).isEqualTo("refresh");
    }

    @Test
    @DisplayName("Should return false when validating expired token")
    void validateToken_shouldReturnFalseForExpiredToken() {
        // Generate a token that expired 5 seconds ago
        String expiredToken = jwtTokenProvider.generateToken(testUserId, testEmail, -5000L, "access");

        assertThat(jwtTokenProvider.validateToken(expiredToken)).isFalse();
    }

    @Test
    @DisplayName("Should return false when validating token with tampered signature")
    void validateToken_shouldReturnFalseForTamperedSignature() {
        String token = jwtTokenProvider.generateAccessToken(testUserId, testEmail);
        String tamperedToken = token + "xyz";

        assertThat(jwtTokenProvider.validateToken(tamperedToken)).isFalse();
    }

    @Test
    @DisplayName("Should return false when validating malformed token")
    void validateToken_shouldReturnFalseForMalformedToken() {
        assertThat(jwtTokenProvider.validateToken("not.a.valid.jwt")).isFalse();
        assertThat(jwtTokenProvider.validateToken("")).isFalse();
        assertThat(jwtTokenProvider.validateToken(null)).isFalse();
        assertThat(jwtTokenProvider.validateToken("   ")).isFalse();
    }

    @Test
    @DisplayName("Should return false when token signed with different key")
    void validateToken_shouldReturnFalseForTokenWithDifferentKey() {
        JwtProperties otherProps = new JwtProperties();
        otherProps.setSecret("999E635266556A586E3272357538782F413F4428472B4B6250645367566B9999");
        otherProps.setAccessTokenExpirationMs(3600000L);
        JwtTokenProvider otherProvider = new JwtTokenProvider(otherProps);

        String tokenFromOther = otherProvider.generateAccessToken(testUserId, testEmail);

        assertThat(jwtTokenProvider.validateToken(tokenFromOther)).isFalse();
    }

    @Test
    @DisplayName("Should handle short secret key safely via SHA-256 fallback")
    void shortSecretKey_shouldFunctionSafely() {
        JwtProperties shortProps = new JwtProperties();
        shortProps.setSecret("short-key");
        shortProps.setAccessTokenExpirationMs(3600000L);
        JwtTokenProvider shortKeyProvider = new JwtTokenProvider(shortProps);

        String token = shortKeyProvider.generateAccessToken(testUserId, testEmail);

        assertThat(token).isNotBlank();
        assertThat(shortKeyProvider.validateToken(token)).isTrue();
        assertThat(shortKeyProvider.getUserIdFromToken(token)).isEqualTo(testUserId);
    }
}
