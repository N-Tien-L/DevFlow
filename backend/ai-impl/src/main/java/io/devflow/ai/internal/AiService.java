package io.devflow.ai.internal;

import io.devflow.ai.api.AiServiceApi;
import io.devflow.ai.api.TaskBreakdownProposal;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The single shared AI brain (ARCHITECTURE.md Section 3): task breakdown, conversational
 * Q&amp;A, and CI/CD failure summarization. Consumed by the chat WebSocket gateway
 * ({@link ChatGatewayController}) and the MCP server ({@link McpToolConfig}) — both are
 * thin adapters and must contain no AI logic of their own.
 *
 * <p>TODO(PRODUCT_SPEC Section 5.2): inject Spring AI's {@code ChatClient} (a bean exists
 * once {@code AI_MODEL_CHAT=openai} and {@code OPENAI_API_KEY} are set) and implement:
 * <ul>
 *   <li>{@link #answerProjectQuery} — ground answers in Board data obtained via
 *       {@code BoardApi} (never Board's internals — Section 10)</li>
 *   <li>{@link #proposeTaskBreakdown} — return an editable proposal; do NOT write to the
 *       board directly. Board changes are requested via events (Section 3: AI service does
 *       not own Board's data)</li>
 *   <li>{@link #summarizeCiFailure} — fetch the failure via {@code GitCiApi}, summarize,
 *       then publish {@code CiFailureSummarizedEvent}</li>
 * </ul>
 */
@Service
public class AiService implements AiServiceApi {

    @Override
    public String answerProjectQuery(UUID projectId, String question) {
        // TODO(PRODUCT_SPEC 5.2b): real LLM-backed answer grounded in project data.
        return "AI assistant is not configured yet — set AI_MODEL_CHAT=openai and OPENAI_API_KEY.";
    }

    @Override
    public TaskBreakdownProposal proposeTaskBreakdown(String description) {
        // TODO(PRODUCT_SPEC 5.2b): real LLM-backed breakdown proposal.
        return new TaskBreakdownProposal(description, List.of());
    }

    @Override
    public String summarizeCiFailure(String pipelineId) {
        // TODO(PRODUCT_SPEC 5.2a): fetch log via GitCiApi and summarize with the LLM.
        return "CI failure summarization is not implemented yet (pipeline " + pipelineId + ").";
    }
}
