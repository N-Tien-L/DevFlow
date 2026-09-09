package io.devflow.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Enables JPA auditing so {@link io.devflow.common.entity.BaseEntity} timestamps are
 * populated automatically. Lives in {@code common} so every module's entities share one
 * auditing setup; picked up by the application's component scan.
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}
