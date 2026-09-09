package io.devflow.auth.internal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Baseline security configuration owned by the Auth module (ARCHITECTURE.md Section 3 —
 * auth logic stays inside this module and is not delegated to a third party, Section 9.1).
 *
 * <p>Currently only opens the endpoints the scaffold must serve anonymously (health
 * checks, Prometheus scrape, WebSocket/MCP entry points) and marks everything else as
 * requiring authentication.
 *
 * <p>TODO(PRODUCT_SPEC Section 5.1 — Accounts &amp; workspaces): replace the placeholder
 * HTTP Basic setup with the real credential mechanism (registration/login, password
 * hashing, session or token issuance) and tighten the WebSocket/MCP rules.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // Stateless REST scaffold — revisit CSRF when browser session auth lands.
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/info", "/actuator/prometheus")
                        .permitAll()
                        .requestMatchers("/api/v1/*/health")
                        .permitAll()
                        // WebSocket (chat gateway) and MCP server entry points.
                        // TODO: authenticate these once login exists (PRODUCT_SPEC 5.1).
                        .requestMatchers("/ws/**", "/sse", "/mcp/**")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                // Placeholder mechanism so protected endpoints return 401 instead of 403.
                .httpBasic(Customizer.withDefaults());
        return http.build();
    }
}
