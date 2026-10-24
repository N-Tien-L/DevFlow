package io.devflow.auth.internal.pat;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Personal access token limits. Bound to prefix {@code devflow.pat}.
 */
@Component
@ConfigurationProperties(prefix = "devflow.pat")
public class PersonalAccessTokenProperties {

    /**
     * Maximum number of live (unrevoked, unexpired) tokens per user.
     */
    private int maxActivePerUser = 10;

    /**
     * {@code last_used_at} is written at most once per this interval per token.
     */
    private Duration lastUsedUpdateInterval = Duration.ofMinutes(5);

    /**
     * Revoked or expired tokens older than this are purged by the nightly cleanup job.
     */
    private Duration retention = Duration.ofDays(30);

    public int getMaxActivePerUser() {
        return maxActivePerUser;
    }

    public void setMaxActivePerUser(int maxActivePerUser) {
        this.maxActivePerUser = maxActivePerUser;
    }

    public Duration getLastUsedUpdateInterval() {
        return lastUsedUpdateInterval;
    }

    public void setLastUsedUpdateInterval(Duration lastUsedUpdateInterval) {
        this.lastUsedUpdateInterval = lastUsedUpdateInterval;
    }

    public Duration getRetention() {
        return retention;
    }

    public void setRetention(Duration retention) {
        this.retention = retention;
    }
}
