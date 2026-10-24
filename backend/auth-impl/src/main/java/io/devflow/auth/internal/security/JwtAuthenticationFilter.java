package io.devflow.auth.internal.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

/**
 * Filter that intercepts incoming HTTP requests, extracts the Bearer JWT token from
 * the {@code Authorization} header, validates it, and sets the authenticated
 * {@link UserPrincipal} into the Spring {@link SecurityContextHolder}.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider tokenProvider;
    private final SessionRevocationService sessionRevocationService;

    public JwtAuthenticationFilter(JwtTokenProvider tokenProvider, SessionRevocationService sessionRevocationService) {
        this.tokenProvider = tokenProvider;
        this.sessionRevocationService = sessionRevocationService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        try {
            String jwt = resolveToken(request);
            // Personal access tokens (dfp_...) are handled by PersonalAccessTokenAuthenticationFilter on MCP
            // paths only; they are never accepted as a JWT.
            if (StringUtils.hasText(jwt) && !PersonalAccessTokenAuthenticationFilter.isPersonalAccessToken(jwt)
                    && tokenProvider.validateToken(jwt)) {
                String tokenType = tokenProvider.getTokenTypeFromToken(jwt);
                // Only allow access tokens for general API request authentication
                if ("access".equalsIgnoreCase(tokenType) || tokenType == null) {
                    var claims = tokenProvider.getClaims(jwt);
                    UUID userId = UUID.fromString(claims.getSubject());
                    String email = claims.get("email", String.class);
                    Instant issuedAt = claims.getIssuedAt() == null ? null : claims.getIssuedAt().toInstant();
                    if (sessionRevocationService.isRevoked(userId, issuedAt)) {
                        // Signed out by a password reset: leave the request unauthenticated (401).
                        log.debug("Access token for user {} predates the last password change", userId);
                        filterChain.doFilter(request, response);
                        return;
                    }

                    UserPrincipal principal = UserPrincipal.create(userId, email);
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                    SecurityContextHolder.getContext().setAuthentication(authentication);
                } else {
                    log.debug("Token presented is of type '{}', not 'access'. Ignoring for authentication.", tokenType);
                }
            }
        } catch (Exception ex) {
            log.error("Could not set user authentication in security context: {}",
                    io.devflow.auth.internal.support.LogMasking.describeFailure(ex));
        }

        filterChain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String bearerToken = request.getHeader(AUTHORIZATION_HEADER);
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith(BEARER_PREFIX)) {
            return bearerToken.substring(BEARER_PREFIX.length()).trim();
        }
        return null;
    }
}
