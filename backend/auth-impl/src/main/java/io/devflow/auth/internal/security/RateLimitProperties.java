package io.devflow.auth.internal.security;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Named token-bucket policies for auth endpoints. Bound to prefix {@code devflow.rate-limit}.
 * Built-in defaults apply unless overridden in configuration.
 */
@Component
@ConfigurationProperties(prefix = "devflow.rate-limit")
public class RateLimitProperties {

    public static final String LOGIN = "login";
    public static final String REGISTER = "register";
    public static final String VERIFY_EMAIL = "verify-email";
    public static final String RESEND_VERIFICATION = "resend-verification";
    public static final String RESEND_VERIFICATION_PER_EMAIL = "resend-verification-per-email";
    // Per-user policies for authenticated workspace endpoints (keyed by user id, not IP).
    public static final String WORKSPACE_CREATE = "workspace-create";
    public static final String WORKSPACE_INVITE = "workspace-invite";
    public static final String INVITATION_ACCEPT = "invitation-accept";
    public static final String WORKSPACE_INVITATION_MANAGE = "workspace-invitation-manage";
    // Personal access tokens (T-038): management per user, MCP usage per token, failed attempts per IP.
    public static final String PAT_CREATE = "pat-create";
    public static final String PAT_MANAGE = "pat-manage";
    public static final String PAT_USE = "pat-use";
    public static final String PAT_AUTH_FAILURE = "pat-auth-failure";
    // Password reset (T-008): per IP on both endpoints, plus per email address on forgot-password.
    public static final String FORGOT_PASSWORD = "forgot-password";
    public static final String FORGOT_PASSWORD_PER_EMAIL = "forgot-password-per-email";
    public static final String RESET_PASSWORD = "reset-password";

    private final Map<String, Policy> policies = new LinkedHashMap<>(Map.ofEntries(
            Map.entry(LOGIN, new Policy(5, Duration.ofMinutes(1))),
            Map.entry(REGISTER, new Policy(3, Duration.ofMinutes(10))),
            Map.entry(VERIFY_EMAIL, new Policy(10, Duration.ofMinutes(1))),
            Map.entry(RESEND_VERIFICATION, new Policy(5, Duration.ofHours(1))),
            Map.entry(RESEND_VERIFICATION_PER_EMAIL, new Policy(3, Duration.ofHours(1))),
            Map.entry(WORKSPACE_CREATE, new Policy(5, Duration.ofHours(1))),
            Map.entry(WORKSPACE_INVITE, new Policy(20, Duration.ofHours(1))),
            Map.entry(INVITATION_ACCEPT, new Policy(10, Duration.ofMinutes(1))),
            Map.entry(WORKSPACE_INVITATION_MANAGE, new Policy(60, Duration.ofMinutes(1))),
            Map.entry(PAT_CREATE, new Policy(10, Duration.ofHours(1))),
            Map.entry(PAT_MANAGE, new Policy(60, Duration.ofMinutes(1))),
            Map.entry(PAT_USE, new Policy(300, Duration.ofMinutes(1))),
            Map.entry(PAT_AUTH_FAILURE, new Policy(20, Duration.ofMinutes(1))),
            Map.entry(FORGOT_PASSWORD, new Policy(5, Duration.ofMinutes(15))),
            Map.entry(FORGOT_PASSWORD_PER_EMAIL, new Policy(3, Duration.ofHours(1))),
            Map.entry(RESET_PASSWORD, new Policy(10, Duration.ofMinutes(15)))
    ));

    public Map<String, Policy> getPolicies() {
        return policies;
    }

    public Policy policy(String name) {
        Policy policy = policies.get(name);
        if (policy == null) {
            throw new IllegalArgumentException("Unknown rate limit policy: " + name);
        }
        return policy;
    }

    /**
     * Allows {@code capacity} requests per {@code period} for each client key.
     */
    public static class Policy {

        private long capacity;
        private Duration period;

        public Policy() {
        }

        public Policy(long capacity, Duration period) {
            this.capacity = capacity;
            this.period = period;
        }

        public long getCapacity() {
            return capacity;
        }

        public void setCapacity(long capacity) {
            this.capacity = capacity;
        }

        public Duration getPeriod() {
            return period;
        }

        public void setPeriod(Duration period) {
            this.period = period;
        }
    }
}
