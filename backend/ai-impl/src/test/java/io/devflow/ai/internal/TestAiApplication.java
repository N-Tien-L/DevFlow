package io.devflow.ai.internal;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot test application for ai-impl module slice tests. Exposes a {@link MeterRegistry} so tests
 * can assert on AI metrics without the actuator exporter.
 */
@SpringBootApplication
public class TestAiApplication {

    @Bean
    MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }
}
