package io.devflow.ai.internal.breakdown;

import io.devflow.ai.api.AiServiceApi;
import io.devflow.ai.api.TaskBreakdownProposal;
import io.devflow.ai.api.TaskBreakdownRequest;
import io.devflow.ai.internal.security.AiActorProvider;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST adapter for task breakdown proposals (T-032). Requires a JWT (enforced by the Auth module's
 * filter chain); returns a proposal only — creating tasks from it is a separate, user-confirmed call
 * to the Board API.
 */
@RestController
@RequestMapping("/api/v1/ai")
public class TaskBreakdownController {

    private final AiServiceApi aiService;
    private final AiActorProvider actorProvider;

    public TaskBreakdownController(AiServiceApi aiService, AiActorProvider actorProvider) {
        this.aiService = aiService;
        this.actorProvider = actorProvider;
    }

    @PostMapping(path = "/task-breakdown", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public TaskBreakdownProposal proposeTaskBreakdown(@Valid @RequestBody TaskBreakdownRequest request) {
        return aiService.proposeTaskBreakdown(actorProvider.requireUserId(), request.workspaceId(),
                request.description());
    }
}
