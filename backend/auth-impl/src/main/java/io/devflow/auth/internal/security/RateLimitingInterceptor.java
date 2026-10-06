package io.devflow.auth.internal.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Spring MVC HandlerInterceptor that applies one token bucket policy per client IP.
 *
 * <p>The client IP is taken from {@link HttpServletRequest#getRemoteAddr()} only. Proxy headers
 * such as {@code X-Forwarded-For} are resolved by the servlet container ({@code server.forward-headers-strategy}),
 * which only trusts configured internal proxies, so clients cannot spoof their IP to bypass limits.
 */
public class RateLimitingInterceptor implements HandlerInterceptor {

    private final RateLimiterService rateLimiterService;
    private final String policyName;

    public RateLimitingInterceptor(RateLimiterService rateLimiterService, String policyName) {
        this.rateLimiterService = rateLimiterService;
        this.policyName = policyName;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String clientIp = request.getRemoteAddr() != null ? request.getRemoteAddr() : "unknown";
        rateLimiterService.checkRateLimit(policyName, clientIp);
        return true;
    }
}
