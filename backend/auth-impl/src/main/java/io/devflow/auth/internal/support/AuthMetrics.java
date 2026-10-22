package io.devflow.auth.internal.support;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Micrometer counters for auth business events (exported via /actuator/prometheus).
 * Falls back to an in-memory registry when no exporter is configured (module slice tests).
 */
@Component
public class AuthMetrics {

    private final MeterRegistry registry;

    public AuthMetrics(ObjectProvider<MeterRegistry> registryProvider) {
        this.registry = registryProvider.getIfAvailable(SimpleMeterRegistry::new);
    }

    /** {@code result}: success | duplicate_email */
    public void registration(String result) {
        registry.counter("devflow.auth.registration", "result", result).increment();
    }

    /** {@code result}: success | invalid | expired */
    public void verification(String result) {
        registry.counter("devflow.auth.email_verification", "result", result).increment();
    }

    /** {@code result}: sent | skipped */
    public void resendVerification(String result) {
        registry.counter("devflow.auth.verification_resend", "result", result).increment();
    }

    /** {@code result}: sent | failed | rejected */
    public void verificationEmail(String result) {
        registry.counter("devflow.auth.verification_email", "result", result).increment();
    }

    /** {@code reason}: unverified */
    public void loginBlocked(String reason) {
        registry.counter("devflow.auth.login_blocked", "reason", reason).increment();
    }

    /**
     * {@code check}: turnstile | disposable_email | mx;
     * {@code result}: passed | rejected | missing | unavailable | unknown
     */
    public void antiAbuse(String check, String result) {
        registry.counter("devflow.auth.anti_abuse", "check", check, "result", result).increment();
    }

    public void purged(String kind, int count) {
        registry.counter("devflow.auth.purged", "kind", kind).increment(count);
    }
}
