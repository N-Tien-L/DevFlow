package io.devflow.ai.internal;

import io.devflow.ai.api.AiServiceApi;
import java.util.UUID;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/**
 * MCP server adapter (ARCHITECTURE.md Section 5): exposes the AI service's capabilities
 * to AI coding agents (Cursor, Claude Code) over the MCP protocol — the product's most
 * distinctive feature (PRODUCT_SPEC.md Section 4).
 *
 * <p>This is a THIN adapter: every MCP tool delegates to {@link AiServiceApi}. It must
 * never reimplement AI logic (PRODUCT_SPEC.md Section 9).
 *
 * <p>The Spring AI MCP Server starter auto-configures the SSE endpoint (/sse) and
 * publishes every {@link ToolCallbackProvider} bean as MCP tools.
 *
 * <p>TODO(PRODUCT_SPEC Section 5.2c): add the remaining tools — "what am I working on"
 * (current task, due date, relevant context) and richer project queries.
 */
@Configuration
public class McpToolConfig {

    @Bean
    public ToolCallbackProvider devflowMcpTools(DevflowMcpTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }

    /** MCP tool surface. One method = one tool visible to AI coding agents. */
    @Component
    public static class DevflowMcpTools {

        private final AiServiceApi aiService;

        public DevflowMcpTools(AiServiceApi aiService) {
            this.aiService = aiService;
        }

        @Tool(description = "Ask a natural-language question about the DevFlow project, "
                + "e.g. 'how many tasks are left this sprint?'")
        public String queryProject(
                @ToolParam(description = "Project UUID") String projectId,
                @ToolParam(description = "Natural-language question") String question) {
            // TODO(PRODUCT_SPEC 5.2c): validate access for the calling agent's identity.
            return aiService.answerProjectQuery(UUID.fromString(projectId), question);
        }

        @Tool(description = "Look up a CI/CD pipeline failure and get a short summary of "
                + "the likely cause, e.g. right after a push fails CI")
        public String lookupCiFailure(@ToolParam(description = "Pipeline run id") String pipelineId) {
            return aiService.summarizeCiFailure(pipelineId);
        }
    }
}
