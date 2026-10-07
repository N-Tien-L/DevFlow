package io.devflow.board.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Adds a validated correlation id to Board API logs and responses. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BoardRequestCorrelationFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Correlation-ID";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        UUID correlationId;
        try {
            correlationId = UUID.fromString(request.getHeader(HEADER));
        } catch (RuntimeException exception) {
            correlationId = UUID.randomUUID();
        }

        request.setAttribute(BoardRequestContext.REQUEST_STARTED_AT_KEY, System.nanoTime());
        MDC.put(BoardRequestContext.CORRELATION_ID_KEY, correlationId.toString());
        String remoteAddress = request.getRemoteAddr();
        MDC.put(BoardRequestContext.clientIpKey(), remoteAddress == null ? "unknown" : remoteAddress);
        response.setHeader(HEADER, correlationId.toString());
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(BoardRequestContext.CORRELATION_ID_KEY);
            MDC.remove(BoardRequestContext.USER_ID_KEY);
            MDC.remove(BoardRequestContext.clientIpKey());
        }
    }
}
