package io.devflow.ai.internal.llm;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

/**
 * Reports the active LLM provider at /actuator/health ({@code components.aiProvider}) without calling it.
 *
 * <p>An open circuit reports the custom status {@code DEGRADED}: the default aggregator ignores statuses
 * outside its order list, so an LLM outage never marks the whole backend unhealthy (restarting the
 * container would not help), while dashboards can still see it.
 */
@Component
public class AiProviderHealthIndicator implements HealthIndicator {

    static final Status DEGRADED = new Status("DEGRADED", "AI provider calls are failing; circuit breaker is open");

    private final AiProviderInfo providerInfo;
    private final AiResilience resilience;

    public AiProviderHealthIndicator(AiProviderInfo providerInfo, AiResilience resilience) {
        this.providerInfo = providerInfo;
        this.resilience = resilience;
    }

    @Override
    public Health health() {
        CircuitBreaker.State state = resilience.circuitBreaker().getState();
        Health.Builder builder = state == CircuitBreaker.State.OPEN || state == CircuitBreaker.State.FORCED_OPEN
                ? Health.status(DEGRADED) : Health.up();
        builder.withDetail("provider", providerInfo.provider())
                .withDetail("model", providerInfo.model())
                .withDetail("circuitBreaker", state.name());
        if (providerInfo.isDeterministic()) {
            builder.withDetail("fallbackReason", providerInfo.fallbackReason());
        }
        return builder.build();
    }
}
