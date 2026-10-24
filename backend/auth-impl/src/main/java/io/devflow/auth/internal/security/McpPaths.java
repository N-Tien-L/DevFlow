package io.devflow.auth.internal.security;

import java.util.Arrays;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Request paths of the MCP server (ARCHITECTURE.md Section 5): the Spring AI WebMVC SSE transport serves the
 * event stream on {@code /sse} and client messages on {@code /mcp/message}. Personal access tokens are only
 * honoured on these paths. Keep in sync with {@code spring.ai.mcp.server.sse-endpoint} /
 * {@code sse-message-endpoint} if those are ever overridden.
 */
public final class McpPaths {

    static final String[] PATTERNS = {"/sse", "/sse/**", "/mcp/**"};

    private static final RequestMatcher MATCHER = new OrRequestMatcher(Arrays.stream(PATTERNS)
            .map(pattern -> (RequestMatcher) new AntPathRequestMatcher(pattern))
            .toList());

    private McpPaths() {
    }

    public static RequestMatcher matcher() {
        return MATCHER;
    }
}
