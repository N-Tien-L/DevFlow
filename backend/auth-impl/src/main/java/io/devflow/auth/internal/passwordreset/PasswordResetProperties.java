package io.devflow.auth.internal.passwordreset;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Password reset settings. Bound to prefix {@code devflow.password-reset}.
 */
@Component
@ConfigurationProperties(prefix = "devflow.password-reset")
public class PasswordResetProperties {

    /**
     * Lifetime of an emailed password reset link.
     */
    private Duration tokenTtl = Duration.ofMinutes(30);

    /**
     * How long each instance caches a user's {@code password_changed_at} when checking JWTs. A reset takes effect
     * immediately on the instance that handled it and within this delay on the others.
     */
    private Duration sessionCheckCacheTtl = Duration.ofSeconds(30);

    public Duration getTokenTtl() {
        return tokenTtl;
    }

    public void setTokenTtl(Duration tokenTtl) {
        this.tokenTtl = tokenTtl;
    }

    public Duration getSessionCheckCacheTtl() {
        return sessionCheckCacheTtl;
    }

    public void setSessionCheckCacheTtl(Duration sessionCheckCacheTtl) {
        this.sessionCheckCacheTtl = sessionCheckCacheTtl;
    }
}
