package io.devflow.ai.internal.llm;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Micrometer meters for LLM calls (exported via /actuator/prometheus). Falls back to an in-memory
 * registry when no exporter is configured (module slice tests).
 */
@Component
public class AiMetrics {

    private final MeterRegistry registry;

    public AiMetrics(ObjectProvider<MeterRegistry> registryProvider) {
        this.registry = registryProvider.getIfAvailable(SimpleMeterRegistry::new);
    }

    Timer.Sample start() {
        return Timer.start(registry);
    }

    /** {@code result}: success | error | circuit_open | rejected */
    void request(AiFeature feature, String result, Timer.Sample sample) {
        registry.counter("devflow.ai.requests", "feature", feature.tag(), "result", result).increment();
        sample.stop(Timer.builder("devflow.ai.request.latency")
                .description("End-to-end LLM call latency, including retries")
                .tags("feature", feature.tag(), "result", result)
                .register(registry));
    }

    void tokens(AiFeature feature, int promptTokens, int completionTokens) {
        registry.counter("devflow.ai.tokens", "feature", feature.tag(), "type", "prompt").increment(promptTokens);
        registry.counter("devflow.ai.tokens", "feature", feature.tag(), "type", "completion")
                .increment(completionTokens);
    }
}
