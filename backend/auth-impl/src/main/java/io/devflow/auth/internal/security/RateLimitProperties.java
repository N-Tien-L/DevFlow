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

    private final Map<String, Policy> policies = new LinkedHashMap<>(Map.of(
            LOGIN, new Policy(5, Duration.ofMinutes(1)),
            REGISTER, new Policy(3, Duration.ofMinutes(10)),
            VERIFY_EMAIL, new Policy(10, Duration.ofMinutes(1)),
            RESEND_VERIFICATION, new Policy(5, Duration.ofHours(1)),
            RESEND_VERIFICATION_PER_EMAIL, new Policy(3, Duration.ofHours(1)),
            WORKSPACE_CREATE, new Policy(5, Duration.ofHours(1)),
            WORKSPACE_INVITE, new Policy(20, Duration.ofHours(1)),
            INVITATION_ACCEPT, new Policy(10, Duration.ofMinutes(1)),
            WORKSPACE_INVITATION_MANAGE, new Policy(60, Duration.ofMinutes(1))
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
