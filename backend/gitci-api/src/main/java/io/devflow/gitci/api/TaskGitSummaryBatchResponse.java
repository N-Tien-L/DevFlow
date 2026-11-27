package io.devflow.gitci.api;

import java.util.List;
import java.util.UUID;

/** Batch response containing summaries only for tasks with Git or CI activity in the workspace. */
public record TaskGitSummaryBatchResponse(
        UUID workspaceId,
        List<TaskGitSummary> items) {

    public TaskGitSummaryBatchResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
