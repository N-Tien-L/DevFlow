package io.devflow.board.internal;

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
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bounds Task mutation JSON bodies, including chunked requests without Content-Length. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class TaskRequestSizeLimitFilter extends OncePerRequestFilter {

    private static final Pattern TASK_CREATE = Pattern.compile("^/api/v1/columns/[^/]+/tasks$");
    private static final Pattern TASK_ITEM = Pattern.compile("^/api/v1/tasks/[^/]+$");
    private static final Pattern TASK_MOVE = Pattern.compile("^/api/v1/tasks/[^/]+/move$");

    private final ObjectMapper objectMapper;
    private final int maxBytes;

    public TaskRequestSizeLimitFilter(
            ObjectMapper objectMapper,
            @Value("${devflow.board.tasks.max-request-bytes:65536}") int maxBytes) {
        if (maxBytes < 1) {
            throw new IllegalArgumentException("Task request body limit must be positive");
        }
        this.objectMapper = objectMapper;
        this.maxBytes = maxBytes;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!isTaskMutation(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        if (request.getContentLengthLong() > maxBytes) {
            writeTooLarge(request, response);
            return;
        }

        byte[] body = request.getInputStream().readNBytes(maxBytes + 1);
        if (body.length > maxBytes) {
            writeTooLarge(request, response);
            return;
        }
        filterChain.doFilter(new BoundedBodyRequest(request, body), response);
    }

    private static boolean isTaskMutation(HttpServletRequest request) {
        String path = request.getServletPath();
        if (path == null || path.isBlank()) {
            path = request.getRequestURI();
            String contextPath = request.getContextPath();
            if (contextPath != null && !contextPath.isBlank() && path.startsWith(contextPath)) {
                path = path.substring(contextPath.length());
            }
        }
        String method = request.getMethod();
        return ("POST".equals(method) && TASK_CREATE.matcher(path).matches())
                || (("PUT".equals(method) || "DELETE".equals(method)) && TASK_ITEM.matcher(path).matches())
                || ("PATCH".equals(method) && TASK_MOVE.matcher(path).matches());
    }

    private void writeTooLarge(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.PAYLOAD_TOO_LARGE, "The task request exceeds the maximum allowed size.");
        problem.setType(java.net.URI.create("https://devflow.local/problems/payload-too-large"));
        problem.setTitle("Payload Too Large");
        problem.setInstance(java.net.URI.create(request.getRequestURI()));
        problem.setProperty("code", "PAYLOAD_TOO_LARGE");
        problem.setProperty("correlationId", BoardRequestContext.correlationId());
        problem.setProperty("timestamp", Instant.now().toString());
        response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), problem);
    }

    private static final class BoundedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        private BoundedBodyRequest(HttpServletRequest request, byte[] body) {
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
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return input.read();
                }

                @Override
                public boolean isFinished() {
                    return input.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    try {
                        if (!isFinished()) {
                            listener.onDataAvailable();
                        }
                        if (isFinished()) {
                            listener.onAllDataRead();
                        }
                    } catch (IOException exception) {
                        listener.onError(exception);
                    }
                }
            };
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }
}
