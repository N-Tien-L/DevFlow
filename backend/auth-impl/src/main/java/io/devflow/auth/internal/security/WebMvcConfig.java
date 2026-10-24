package io.devflow.auth.internal.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC configuration for registering per-endpoint rate limiting interceptors.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final RateLimiterService rateLimiterService;

    public WebMvcConfig(RateLimiterService rateLimiterService) {
        this.rateLimiterService = rateLimiterService;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        register(registry, RateLimitProperties.LOGIN, "/api/v1/auth/login");
        register(registry, RateLimitProperties.REGISTER, "/api/v1/auth/register");
        register(registry, RateLimitProperties.VERIFY_EMAIL, "/api/v1/auth/verify-email");
        register(registry, RateLimitProperties.RESEND_VERIFICATION, "/api/v1/auth/resend-verification");
        register(registry, RateLimitProperties.FORGOT_PASSWORD, "/api/v1/auth/forgot-password");
        register(registry, RateLimitProperties.RESET_PASSWORD, "/api/v1/auth/reset-password");
    }

    private void register(InterceptorRegistry registry, String policyName, String path) {
        registry.addInterceptor(new RateLimitingInterceptor(rateLimiterService, policyName))
                .addPathPatterns(path);
    }
}
