package io.devflow.auth.internal;

import io.devflow.common.config.JpaAuditingConfig;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * Spring Boot test application configuration for auth-impl module slice tests.
 */
@SpringBootApplication
@Import(JpaAuditingConfig.class)
public class TestAuthApplication {
}
