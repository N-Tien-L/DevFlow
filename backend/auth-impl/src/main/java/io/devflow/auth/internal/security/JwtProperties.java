package io.devflow.auth.internal.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for JWT authentication in DevFlow.
 * Bound to prefix {@code devflow.jwt}.
 */
@Component
@ConfigurationProperties(prefix = "devflow.jwt")
public class JwtProperties {

    /**
     * Secret key for signing HMAC-SHA256 (HS256) tokens.
     * Default key is a 256-bit key for local development.
     */
    private String secret = "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970";

    /**
     * Access token expiration in milliseconds (default: 3600000 ms = 1 hour).
     */
    private long accessTokenExpirationMs = 3600000L;

    /**
     * Refresh token expiration in milliseconds (default: 604800000 ms = 7 days).
     */
    private long refreshTokenExpirationMs = 604800000L;

    /**
     * Issuer claim value included in generated tokens.
     */
    private String issuer = "devflow";

    public JwtProperties() {
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public long getAccessTokenExpirationMs() {
        return accessTokenExpirationMs;
    }

    public void setAccessTokenExpirationMs(long accessTokenExpirationMs) {
        this.accessTokenExpirationMs = accessTokenExpirationMs;
    }

    public long getRefreshTokenExpirationMs() {
        return refreshTokenExpirationMs;
    }

    public void setRefreshTokenExpirationMs(long refreshTokenExpirationMs) {
        this.refreshTokenExpirationMs = refreshTokenExpirationMs;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }
}
