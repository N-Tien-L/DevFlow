package io.devflow.ai.internal;

import io.devflow.common.config.JpaAuditingConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Spring Boot test application for ai-impl module slice tests. Exposes a {@link MeterRegistry} so tests
 * can assert on AI metrics without the actuator exporter.
 */
@SpringBootApplication
@Import(JpaAuditingConfig.class)
public class TestAiApplication {

    @Bean
    MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }
}
