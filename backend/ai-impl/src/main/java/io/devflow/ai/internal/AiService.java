package io.devflow.ai.internal;

import io.devflow.ai.api.AiServiceApi;
import io.devflow.ai.api.ProjectQueryAnswer;
import io.devflow.ai.api.TaskBreakdownProposal;
import io.devflow.ai.internal.breakdown.TaskBreakdownService;
import io.devflow.ai.internal.ci.CiFailureSummarizerService;
import io.devflow.ai.internal.qa.ProjectQueryService;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The single shared AI brain (ARCHITECTURE.md Section 3): task breakdown, conversational
 * Q&amp;A, and CI/CD failure summarization. Task breakdown (T-032) lives in
 * {@link TaskBreakdownService}: a proposal only, never written to the board. Project Q&amp;A (T-033) lives in
 * {@link ProjectQueryService}: membership first, then answers grounded in Board data read through
 * {@code BoardApi} (never Board's internals — Section 10). CI failure summarization (T-031) lives in
 * {@link CiFailureSummarizerService}: redacts secrets, summarizes to 3 bullets under 100 words, and publishes
 * {@code CiFailureSummarizedEvent}. Consumed by the chat WebSocket gateway ({@link ChatGatewayController})
 * and the MCP server ({@link McpToolConfig}) — both are thin adapters and must contain no AI logic of their own.
 */
@Service
public class AiService implements AiServiceApi {

    private final TaskBreakdownService taskBreakdownService;
    private final ProjectQueryService projectQueryService;
    private final CiFailureSummarizerService ciFailureSummarizerService;

    public AiService(
            TaskBreakdownService taskBreakdownService,
            ProjectQueryService projectQueryService,
            CiFailureSummarizerService ciFailureSummarizerService) {
        this.taskBreakdownService = taskBreakdownService;
        this.projectQueryService = projectQueryService;
        this.ciFailureSummarizerService = ciFailureSummarizerService;
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
        return ciFailureSummarizerService.summarizeFromApi(pipelineId);
    }
}
