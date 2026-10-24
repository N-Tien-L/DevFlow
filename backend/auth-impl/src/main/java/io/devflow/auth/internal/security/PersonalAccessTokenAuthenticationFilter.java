package io.devflow.auth.internal.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.devflow.auth.internal.PersonalAccessTokenService;
import io.devflow.auth.internal.exception.RateLimitExceededException;
import io.devflow.auth.internal.pat.PersonalAccessTokenCodec;
import io.devflow.auth.internal.support.LogMasking;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates MCP clients that send {@code Authorization: Bearer dfp_...} (T-038).
 *
 * <p>Runs only on {@link McpPaths}; anywhere else a personal access token is ignored (and the JWT filter skips
 * it too), so it can never stand in for a JWT on the rest of the API. A presented but unusable token is
 * answered with 401 right away, without saying whether it was unknown, revoked or expired.
 *
 * <p>The security context is also stored as a request attribute so the async dispatches of the SSE stream
 * keep the authenticated user.
 */
@Component
public class PersonalAccessTokenAuthenticationFilter extends OncePerRequestFilter {

    static final String INVALID_TOKEN_DETAIL = "Invalid, expired or revoked access token";

    private static final Logger log = LoggerFactory.getLogger(PersonalAccessTokenAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final PersonalAccessTokenService tokenService;
    private final ObjectMapper objectMapper;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();
    private final SecurityContextRepository contextRepository = new RequestAttributeSecurityContextRepository();

    public PersonalAccessTokenAuthenticationFilter(PersonalAccessTokenService tokenService, ObjectMapper objectMapper) {
        this.tokenService = tokenService;
        this.objectMapper = objectMapper;
    }

    /**
     * True when the request carries a personal access token rather than a JWT.
     */
    static boolean isPersonalAccessToken(String bearerValue) {
        return bearerValue != null && bearerValue.startsWith(PersonalAccessTokenCodec.PREFIX);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !McpPaths.matcher().matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)
                || !isPersonalAccessToken(header.substring(BEARER_PREFIX.length()).trim())) {
            chain.doFilter(request, response);
            return;
        }

        String clientIp = request.getRemoteAddr() != null ? request.getRemoteAddr() : "unknown";
        Optional<PersonalAccessTokenService.AuthenticatedToken> authenticated;
        try {
            authenticated = tokenService.authenticate(header.substring(BEARER_PREFIX.length()).trim(), clientIp);
        } catch (RateLimitExceededException e) {
            writeProblem(request, response, HttpStatus.TOO_MANY_REQUESTS.value(), "Too Many Requests",
                    e.getMessage(), "RATE_LIMITED", e.getRetryAfterSeconds());
            return;
        } catch (RuntimeException e) {
            // Fail closed. Exception text is not logged: it may echo request data.
            log.error("Access token authentication failed: {}", LogMasking.describeFailure(e));
            writeProblem(request, response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Service Unavailable",
                    "Authentication is temporarily unavailable. Please retry.", "AUTHENTICATION_UNAVAILABLE", 30L);
            return;
        }

        if (authenticated.isEmpty()) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"");
            writeProblem(request, response, HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized",
                    INVALID_TOKEN_DETAIL, "INVALID_ACCESS_TOKEN", null);
            return;
        }

        PersonalAccessTokenService.AuthenticatedToken token = authenticated.get();
        UserPrincipal principal = UserPrincipal.create(token.userId(), token.email());
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContext context = contextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        contextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);

        chain.doFilter(request, response);
    }

    private void writeProblem(HttpServletRequest request, HttpServletResponse response, int status, String title,
                              String detail, String code, Long retryAfterSeconds) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", title);
        body.put("status", status);
        body.put("detail", detail);
        body.put("instance", request.getRequestURI());
        body.put("code", code);
        body.put("timestamp", Instant.now().toString());
        if (retryAfterSeconds != null) {
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
            body.put("retryAfterSeconds", retryAfterSeconds);
        }
        String correlationId = MDC.get("correlationId");
        if (correlationId != null) {
            body.put("correlationId", correlationId);
        }
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
