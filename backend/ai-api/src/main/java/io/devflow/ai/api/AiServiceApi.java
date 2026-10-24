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
     * agents via MCP, Section 5.2c). Answers only from the project's board data.
     *
     * @param userId    the authenticated caller; must be a member of {@code projectId}, checked before
     *                  any project data is read
     * @param projectId the project (workspace) asked about
     * @param question  at most {@link ProjectQueryRequest#MAX_QUESTION_LENGTH} characters; treated as data
     */
    ProjectQueryAnswer answerProjectQuery(UUID userId, UUID projectId, String question);

    /**
     * Turns a requirement or error log into an editable proposal of 3-5 subtasks
     * (PRODUCT_SPEC Section 5.2b — task creation &amp; breakdown from description). Never creates tasks.
     *
     * @param userId      the authenticated caller; must be a member of {@code workspaceId}
     * @param workspaceId workspace the proposal is for (membership check, per-user AI rate limit)
     * @param description at most {@link TaskBreakdownRequest#MAX_DESCRIPTION_LENGTH} characters; treated as data
     */
    TaskBreakdownProposal proposeTaskBreakdown(UUID userId, UUID workspaceId, String description);

    /**
     * Summarizes a failed CI run's log into a short likely-cause statement
     * (PRODUCT_SPEC Section 5.2a; queried in-editor via MCP, Section 5.2c).
     */
    String summarizeCiFailure(String pipelineId);
}
