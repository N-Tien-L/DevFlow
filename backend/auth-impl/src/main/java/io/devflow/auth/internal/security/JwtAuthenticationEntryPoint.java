package io.devflow.auth.internal.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Custom authentication entry point that returns a structured JSON 401 Unauthorized
 * response whenever an unauthenticated client attempts to access a protected resource.
 */
@Component
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public JwtAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException) throws IOException {

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);

        String message = authException != null && authException.getMessage() != null
                ? authException.getMessage()
                : "Full authentication is required to access this resource";

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", "Unauthorized");
        body.put("status", HttpServletResponse.SC_UNAUTHORIZED);
        body.put("detail", message);
        body.put("instance", request.getRequestURI());
        // Backwards-compatible / convenience properties for tests and clients
        body.put("error", "Unauthorized");
        body.put("message", message);
        body.put("path", request.getRequestURI());
        body.put("timestamp", Instant.now().toString());
        body.put("code", "AUTHENTICATION_REQUIRED");
        String correlationId = MDC.get("correlationId");
        if (correlationId != null) {
            body.put("correlationId", correlationId);
        }

        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
