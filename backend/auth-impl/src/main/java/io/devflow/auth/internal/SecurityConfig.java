package io.devflow.auth.internal;

import io.devflow.auth.internal.security.JwtAuthenticationEntryPoint;
import io.devflow.auth.internal.security.JwtAuthenticationFilter;
import io.devflow.auth.internal.security.McpPaths;
import io.devflow.auth.internal.security.PersonalAccessTokenAuthenticationFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 6 configuration owned by the Auth module (ARCHITECTURE.md Section 3 & 9.1).
 * Configures stateless JWT authentication filter chain, public access rules, and password encoding.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationEntryPoint entryPoint;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final PersonalAccessTokenAuthenticationFilter patAuthenticationFilter;

    public SecurityConfig(JwtAuthenticationEntryPoint entryPoint, JwtAuthenticationFilter jwtAuthenticationFilter,
                          PersonalAccessTokenAuthenticationFilter patAuthenticationFilter) {
        this.entryPoint = entryPoint;
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.patAuthenticationFilter = patAuthenticationFilter;
    }

    /**
     * The PAT filter is a bean only so it can be injected; it must run inside the security chain, not also as a
     * plain servlet filter.
     */
    @Bean
    public FilterRegistrationBean<PersonalAccessTokenAuthenticationFilter> patFilterRegistration() {
        FilterRegistrationBean<PersonalAccessTokenAuthenticationFilter> registration =
                new FilterRegistrationBean<>(patAuthenticationFilter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(entryPoint))
                .authorizeHttpRequests(auth -> auth
                        // Async dispatches continue a request already authorised on its first dispatch (MCP SSE
                        // stream); re-checking them would drop a JWT, which lives only on the original thread.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        // Observability endpoints
                        .requestMatchers("/actuator/health", "/actuator/info", "/actuator/prometheus").permitAll()
                        // Module health checks
                        .requestMatchers("/api/v1/*/health").permitAll()
                        // Specific protected endpoints within auth module (for T-004)
                        .requestMatchers("/api/v1/auth/me", "/api/v1/auth/workspaces/**", "/api/v1/auth/workspaces",
                                "/api/v1/auth/invitations/**", "/api/v1/auth/tokens", "/api/v1/auth/tokens/**")
                        .authenticated()
                        // Public auth endpoints (registration, login, refresh, etc.)
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        // MCP server (T-038): a personal access token or a JWT is required
                        .requestMatchers(McpPaths.matcher()).authenticated()
                        // WebSocket entry point (STOMP CONNECT is authenticated by StompAuthenticationInterceptor)
                        .requestMatchers("/ws/**").permitAll()
                        // All other endpoints require valid JWT authentication
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(patAuthenticationFilter, JwtAuthenticationFilter.class);

        return http.build();
    }
}
