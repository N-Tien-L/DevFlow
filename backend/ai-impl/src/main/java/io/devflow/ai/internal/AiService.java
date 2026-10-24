package io.devflow.ai.internal;

import io.devflow.ai.api.AiServiceApi;
import io.devflow.ai.api.TaskBreakdownProposal;
import io.devflow.ai.internal.breakdown.TaskBreakdownService;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The single shared AI brain (ARCHITECTURE.md Section 3): task breakdown, conversational
 * Q&amp;A, and CI/CD failure summarization. Task breakdown (T-032) lives in
 * {@link TaskBreakdownService}: a proposal only, never written to the board. Consumed by the
 * chat WebSocket gateway ({@link ChatGatewayController}) and the MCP server ({@link McpToolConfig}) — both are
 * thin adapters and must contain no AI logic of their own.
 *
 * <p>TODO(PRODUCT_SPEC Section 5.2): call the LLM through
 * {@link io.devflow.ai.internal.llm.AiChatGateway} (shared {@code ChatClient} with timeouts, retry,
 * circuit breaker and metrics; deterministic fallback when no provider is configured) and implement:
 * <ul>
 *   <li>{@link #answerProjectQuery} — ground answers in Board data obtained via
 *       {@code BoardApi} (never Board's internals — Section 10)</li>
 *   <li>{@link #summarizeCiFailure} — fetch the failure via {@code GitCiApi}, summarize,
 *       then publish {@code CiFailureSummarizedEvent}</li>
 * </ul>
 */
@Service
public class AiService implements AiServiceApi {

    private final TaskBreakdownService taskBreakdownService;

    public AiService(TaskBreakdownService taskBreakdownService) {
        this.taskBreakdownService = taskBreakdownService;
    }

    @Override
    public String answerProjectQuery(UUID projectId, String question) {
        // TODO(PRODUCT_SPEC 5.2b): real LLM-backed answer grounded in project data.
        return "AI assistant Q&A is not implemented yet.";
    }

    @Override
    public TaskBreakdownProposal proposeTaskBreakdown(UUID userId, UUID workspaceId, String description) {
        return taskBreakdownService.propose(userId, workspaceId, description);
    }

    @Override
    public String summarizeCiFailure(String pipelineId) {
        // TODO(PRODUCT_SPEC 5.2a): fetch log via GitCiApi and summarize with the LLM.
        return "CI failure summarization is not implemented yet (pipeline " + pipelineId + ").";
    }
}
