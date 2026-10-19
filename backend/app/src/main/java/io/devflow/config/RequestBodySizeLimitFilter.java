package io.devflow.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects oversized API request bodies with RFC 7807 413 before any controller binds or
 * deserializes them (PRODUCT_SPEC Section 8, strict payload size limits). Declared lengths are
 * checked up front; chunked bodies are buffered up to the limit, which stays small by design.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestBodySizeLimitFilter extends OncePerRequestFilter {

    private static final Set<String> BODY_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final ObjectMapper objectMapper;
    private final int maxBodyBytes;
    private final List<String> excludedPathPrefixes;

    public RequestBodySizeLimitFilter(
            ObjectMapper objectMapper,
            @Value("${devflow.http.max-request-body-bytes:65536}") int maxBodyBytes,
            @Value("${devflow.http.body-limit-excluded-paths:}") List<String> excludedPathPrefixes) {
        this.objectMapper = objectMapper;
        this.maxBodyBytes = maxBodyBytes;
        this.excludedPathPrefixes = excludedPathPrefixes.stream().filter(prefix -> !prefix.isBlank()).toList();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !BODY_METHODS.contains(request.getMethod())
                || !path.startsWith("/api/")
                || excludedPathPrefixes.stream().anyMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        long declaredLength = request.getContentLengthLong();
        if (declaredLength > maxBodyBytes) {
            writeTooLarge(request, response);
            return;
        }
        if (declaredLength >= 0) {
            filterChain.doFilter(request, response);
            return;
        }
        byte[] body = request.getInputStream().readNBytes(maxBodyBytes + 1);
        if (body.length > maxBodyBytes) {
            writeTooLarge(request, response);
            return;
        }
        filterChain.doFilter(new CachedBodyRequest(request, body), response);
    }

    private void writeTooLarge(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE,
                "The request body exceeds the maximum allowed size of " + maxBodyBytes + " bytes.");
        problem.setType(URI.create("https://devflow.local/problems/payload-too-large"));
        problem.setTitle("Payload Too Large");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", "PAYLOAD_TOO_LARGE");
        String correlationId = MDC.get("correlationId");
        if (correlationId != null) {
            problem.setProperty("correlationId", correlationId);
        }
        problem.setProperty("timestamp", Instant.now().toString());
        response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), problem);
    }

    /** Replays a body that was already read (and bounded) by this filter. */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }

        @Override
        public ServletInputStream getInputStream() {
            InputStream source = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() throws IOException {
                    return source.read();
                }

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    return source.read(buffer, offset, length);
                }

                @Override
                public boolean isFinished() {
                    return available() == 0;
                }

                @Override
                public int available() {
                    try {
                        return source.available();
                    } catch (IOException exception) {
                        return 0;
                    }
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("Asynchronous reads are not supported.");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }
}
