package io.devflow.ai.internal;

import io.devflow.ai.api.AiServiceApi;
import io.devflow.ai.api.ProjectQueryAnswer;
import io.devflow.ai.api.TaskBreakdownProposal;
import io.devflow.ai.internal.breakdown.TaskBreakdownService;
import io.devflow.ai.internal.qa.ProjectQueryService;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The single shared AI brain (ARCHITECTURE.md Section 3): task breakdown, conversational
 * Q&amp;A, and CI/CD failure summarization. Task breakdown (T-032) lives in
 * {@link TaskBreakdownService}: a proposal only, never written to the board. Project Q&amp;A (T-033) lives in
 * {@link ProjectQueryService}: membership first, then answers grounded in Board data read through
 * {@code BoardApi} (never Board's internals — Section 10). Consumed by the
 * chat WebSocket gateway ({@link ChatGatewayController}) and the MCP server ({@link McpToolConfig}) — both are
 * thin adapters and must contain no AI logic of their own.
 *
 * <p>TODO(PRODUCT_SPEC Section 5.2): call the LLM through
 * {@link io.devflow.ai.internal.llm.AiChatGateway} (shared {@code ChatClient} with timeouts, retry,
 * circuit breaker and metrics; deterministic fallback when no provider is configured) and implement:
 * <ul>
 *   <li>{@link #summarizeCiFailure} — fetch the failure via {@code GitCiApi}, summarize,
 *       then publish {@code CiFailureSummarizedEvent}</li>
 * </ul>
 */
@Service
public class AiService implements AiServiceApi {

    private final TaskBreakdownService taskBreakdownService;
    private final ProjectQueryService projectQueryService;

    public AiService(TaskBreakdownService taskBreakdownService, ProjectQueryService projectQueryService) {
        this.taskBreakdownService = taskBreakdownService;
        this.projectQueryService = projectQueryService;
    }

    @Override
    public ProjectQueryAnswer answerProjectQuery(UUID userId, UUID projectId, String question) {
        return projectQueryService.answer(userId, projectId, question);
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
