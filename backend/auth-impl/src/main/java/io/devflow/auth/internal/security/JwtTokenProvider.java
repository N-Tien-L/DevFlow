package io.devflow.auth.internal.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Service component responsible for generating, validating, and extracting claims
 * from JSON Web Tokens (JWT) using HMAC-SHA256 (HS256).
 */
@Component
public class JwtTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenProvider.class);

    private final JwtProperties jwtProperties;

    public JwtTokenProvider(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
    }

    @PostConstruct
    public void validateConfiguration() {
        String secret = jwtProperties.getSecret();
        if (secret == null || secret.trim().isEmpty()) {
            throw new IllegalStateException(
                    "JWT secret key is not configured. Please define 'devflow.jwt.secret' in application.yml or set JWT_SECRET environment variable.");
        }
    }

    /**
     * Generates a short-lived access token for an authenticated user.
     *
     * @param userId user UUID
     * @param email  user email
     * @return signed JWT string
     */
    public String generateAccessToken(UUID userId, String email) {
        return generateToken(userId, email, jwtProperties.getAccessTokenExpirationMs(), "access");
    }

    /**
     * Generates a long-lived refresh token for an authenticated user.
     *
     * @param userId user UUID
     * @param email  user email
     * @return signed JWT string
     */
    public String generateRefreshToken(UUID userId, String email) {
        return generateToken(userId, email, jwtProperties.getRefreshTokenExpirationMs(), "refresh");
    }

    /**
     * Generates a token with specified expiration duration and token type claim.
     */
    public String generateToken(UUID userId, String email, long expirationMs, String tokenType) {
        Instant now = Instant.now();
        Instant expiry = now.plusMillis(expirationMs);

        return Jwts.builder()
                .subject(userId.toString())
                .claim("email", email)
                .claim("type", tokenType)
                .issuer(jwtProperties.getIssuer())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(getSigningKey())
                .compact();
    }

    /**
     * Validates whether the given JWT token has a valid signature and has not expired.
     *
     * @param token JWT string
     * @return true if valid, false otherwise
     */
    public boolean validateToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        try {
            Jwts.parser()
                    .verifyWith(getSigningKey())
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Invalid JWT token: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Parses and returns the claims payload from a signed JWT.
     *
     * @param token JWT string
     * @return claims payload
     */
    public Claims getClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * Extracts the user UUID from the token subject.
     */
    public UUID getUserIdFromToken(String token) {
        Claims claims = getClaims(token);
        return UUID.fromString(claims.getSubject());
    }

    /**
     * Extracts the user email claim from the token.
     */
    public String getEmailFromToken(String token) {
        Claims claims = getClaims(token);
        return claims.get("email", String.class);
    }

    /**
     * Extracts the token type claim (e.g. "access" or "refresh").
     */
    public String getTokenTypeFromToken(String token) {
        Claims claims = getClaims(token);
        return claims.get("type", String.class);
    }

    private SecretKey getSigningKey() {
        String secret = jwtProperties.getSecret();
        if (secret == null || secret.trim().isEmpty()) {
            throw new IllegalStateException(
                    "JWT secret key is not configured. Please define 'devflow.jwt.secret' in application.yml or set JWT_SECRET environment variable.");
        }
        byte[] keyBytes;
        try {
            keyBytes = Decoders.BASE64.decode(secret);
            if (keyBytes.length < 32) {
                keyBytes = secret.getBytes(StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        }

        if (keyBytes.length < 32) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                keyBytes = md.digest(keyBytes);
            } catch (NoSuchAlgorithmException ex) {
                throw new IllegalStateException("SHA-256 algorithm not available", ex);
            }
        }

        return Keys.hmacShaKeyFor(keyBytes);
    }
}
