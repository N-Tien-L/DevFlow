package io.devflow.ai.internal.breakdown;

import io.devflow.ai.api.TaskBreakdownProposal;
import io.devflow.ai.api.TaskBreakdownRequest;
import io.devflow.ai.internal.config.AiProperties;
import io.devflow.ai.internal.exception.AiApiException;
import io.devflow.ai.internal.llm.AiChatGateway;
import io.devflow.ai.internal.llm.AiFeature;
import io.devflow.ai.internal.llm.AiMalformedOutputException;
import io.devflow.ai.internal.llm.AiStructuredCompletion;
import io.devflow.ai.internal.security.AiRateLimiter;
import io.devflow.ai.internal.security.WorkspaceMembershipChecker;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Proposes a 3-5 subtask breakdown of a requirement (T-032). Read-only toward the board: it returns a
 * proposal for the user to edit and never creates tasks or publishes task events.
 *
 * <p>Order of checks: input validation (400), per-user AI rate limit (429), workspace membership (403),
 * then the model. A malformed or invalid answer is asked for again up to
 * {@code devflow.ai.breakdown.max-output-attempts} times in total, then fails with 502.
 */
@Service
public class TaskBreakdownService {

    private static final Logger log = LoggerFactory.getLogger(TaskBreakdownService.class);
    // Control characters except tab and newline (CR dropped; CRLF becomes LF).
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\n\\t]]");

    private final AiChatGateway gateway;
    private final AiRateLimiter rateLimiter;
    private final WorkspaceMembershipChecker membership;
    private final AiProperties properties;

    public TaskBreakdownService(AiChatGateway gateway, AiRateLimiter rateLimiter,
            WorkspaceMembershipChecker membership, AiProperties properties) {
        this.gateway = gateway;
        this.rateLimiter = rateLimiter;
        this.membership = membership;
        this.properties = properties;
    }

    public TaskBreakdownProposal propose(UUID userId, UUID workspaceId, String description) {
        if (userId == null) {
            throw AiApiException.authenticationRequired();
        }
        if (workspaceId == null) {
            throw AiApiException.badRequest("WORKSPACE_REQUIRED", "workspaceId is required.");
        }
        String requirement = sanitize(description);
        if (requirement.isEmpty()) {
            throw AiApiException.badRequest("AI_DESCRIPTION_REQUIRED", "Description is required.");
        }
        if (requirement.length() > TaskBreakdownRequest.MAX_DESCRIPTION_LENGTH) {
            throw AiApiException.badRequest("AI_DESCRIPTION_TOO_LONG", "Description must not exceed "
                    + TaskBreakdownRequest.MAX_DESCRIPTION_LENGTH + " characters.");
        }
        rateLimiter.check(userId, AiFeature.TASK_BREAKDOWN.tag());
        membership.requireMember(userId, workspaceId);

        String userPrompt = TaskBreakdownPrompts.user(requirement);
        int attempts = properties.getBreakdown().getMaxOutputAttempts();
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                AiStructuredCompletion<TaskBreakdownProposal> completion = gateway.completeEntity(
                        AiFeature.TASK_BREAKDOWN, TaskBreakdownPrompts.SYSTEM, userPrompt,
                        TaskBreakdownProposal.class, properties.getBreakdown().getMaxOutputTokens(),
                        TaskBreakdownValidator::normalize);
                log.info("Task breakdown proposed userId={} workspaceId={} subtasks={} attempt={} model={}",
                        userId, workspaceId, completion.entity().subtasks().size(), attempt, completion.model());
                return completion.entity();
            } catch (AiMalformedOutputException malformed) {
                log.warn("Task breakdown answer unusable userId={} workspaceId={} attempt={}/{}",
                        userId, workspaceId, attempt, attempts);
            }
        }
        throw AiApiException.outputInvalid();
    }

    private static String sanitize(String description) {
        return CONTROL.matcher(Objects.requireNonNullElse(description, "")).replaceAll("").strip();
    }
}
