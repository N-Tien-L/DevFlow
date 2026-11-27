package io.devflow.ai.internal;

import io.devflow.common.config.JpaAuditingConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/**
 * Spring Boot test application for ai-impl module slice tests. Exposes a {@link MeterRegistry} so tests
 * can assert on AI metrics without the actuator exporter, and a mock {@link SimpMessagingTemplate}
 * so WebSocket services can boot in slice tests.
 */
@SpringBootApplication
@Import(JpaAuditingConfig.class)
public class TestAiApplication {

    @Bean
    MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    SimpMessagingTemplate simpMessagingTemplate() {
        return Mockito.mock(SimpMessagingTemplate.class);
    }
}
