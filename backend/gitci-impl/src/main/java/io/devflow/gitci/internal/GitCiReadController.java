package io.devflow.gitci.internal;

import io.devflow.gitci.api.TaskGitActivityResponse;
import io.devflow.gitci.api.TaskGitSummaryBatchResponse;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated Git &amp; CI read endpoints backing Kanban task badges and detail breakdown. */
@RestController
@RequestMapping("/api/v1/gitci/workspaces/{workspaceId}")
class GitCiReadController {

    static final int MAX_BATCH_TASK_IDS = 50;
    private static final int MAX_RAW_TASK_ID_TOKENS = 100;
    private static final Pattern STRICT_UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final GitCiReadService readService;

    GitCiReadController(GitCiReadService readService) {
        this.readService = readService;
    }

    @GetMapping("/task-summaries")
    TaskGitSummaryBatchResponse getTaskSummaries(
            @PathVariable("workspaceId") UUID workspaceId,
            @RequestParam(name = "taskIds", required = false) List<String> rawTaskIds) {
        List<UUID> taskIds = parseTaskIds(rawTaskIds);
        return readService.getTaskSummaries(workspaceId, taskIds);
    }

    @GetMapping("/tasks/{taskId}/activity")
    TaskGitActivityResponse getTaskActivity(
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("taskId") UUID taskId,
            @RequestParam(name = "commitsLimit", defaultValue = "20") int commitsLimit,
            @RequestParam(name = "prsLimit", defaultValue = "20") int prsLimit,
            @RequestParam(name = "ciRunsLimit", defaultValue = "10") int ciRunsLimit) {
        validateRange("commitsLimit", commitsLimit, 1, 50);
        validateRange("prsLimit", prsLimit, 1, 50);
        validateRange("ciRunsLimit", ciRunsLimit, 1, 20);
        return readService.getTaskActivity(workspaceId, taskId, commitsLimit, prsLimit, ciRunsLimit);
    }

    private static List<UUID> parseTaskIds(List<String> rawTaskIds) {
        if (rawTaskIds == null || rawTaskIds.isEmpty()) {
            throw GitCiReadException.validationFailed("taskIds must contain between 1 and 50 UUID values.");
        }
        if (rawTaskIds.size() > MAX_RAW_TASK_ID_TOKENS) {
            throw GitCiReadException.validationFailed("Too many taskIds supplied in a single request.");
        }
        LinkedHashSet<UUID> parsed = new LinkedHashSet<>();
        for (String token : rawTaskIds) {
            if (token == null) {
                throw GitCiReadException.validationFailed("Each taskId must be a valid UUID.");
            }
            String trimmed = token.trim();
            if (!STRICT_UUID.matcher(trimmed).matches()) {
                throw GitCiReadException.validationFailed("Each taskId must be a valid UUID.");
            }
            parsed.add(UUID.fromString(trimmed));
            if (parsed.size() > MAX_BATCH_TASK_IDS) {
                throw GitCiReadException.validationFailed("At most 50 distinct taskIds are allowed per request.");
            }
        }
        if (parsed.isEmpty()) {
            throw GitCiReadException.validationFailed("taskIds must contain between 1 and 50 UUID values.");
        }
        return new ArrayList<>(parsed);
    }

    private static void validateRange(String field, int value, int min, int max) {
        if (value < min || value > max) {
            throw GitCiReadException.validationFailed(field + " must be between " + min + " and " + max + ".");
        }
    }
}
