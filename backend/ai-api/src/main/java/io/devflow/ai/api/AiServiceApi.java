package io.devflow.ai.api;

import java.util.UUID;

/**
 * Public interface of the AI service module — the single shared brain behind BOTH the
 * in-app chat assistant and the MCP server (ARCHITECTURE.md Section 3, PRODUCT_SPEC.md
 * Section 9: "a single shared capability with two client types — avoid implementing
 * duplicate logic for each").
 *
 * <p>Both access paths in the API layer (chat WebSocket gateway and MCP server) are thin
 * adapters that call these methods; any new capability belongs here so both paths get it
 * (ARCHITECTURE.md Section 10).
 */
public interface AiServiceApi {

    /**
     * Natural-language project Q&amp;A — "how many tasks are left this sprint?",
     * "who owns the payments module?" (PRODUCT_SPEC Section 5.2b; also exposed to coding
     * agents via MCP, Section 5.2c).
     */
    String answerProjectQuery(UUID projectId, String question);

    /**
     * Turns a requirement or error log into an editable subtask proposal
     * (PRODUCT_SPEC Section 5.2b — task creation &amp; breakdown from description).
     */
    TaskBreakdownProposal proposeTaskBreakdown(String description);

    /**
     * Summarizes a failed CI run's log into a short likely-cause statement
     * (PRODUCT_SPEC Section 5.2a; queried in-editor via MCP, Section 5.2c).
     */
    String summarizeCiFailure(String pipelineId);
}
