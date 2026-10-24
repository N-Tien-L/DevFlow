package io.devflow.ai.internal.qa;

import io.devflow.ai.api.AiServiceApi;
import io.devflow.ai.api.ProjectQueryAnswer;
import io.devflow.ai.api.ProjectQueryRequest;
import io.devflow.ai.internal.security.AiActorProvider;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST adapter for grounded project Q&amp;A (T-033). Requires a JWT (enforced by the Auth module's filter
 * chain); the caller must be a member of the project's workspace. Read-only: nothing on the board changes.
 */
@RestController
@RequestMapping("/api/v1/ai")
public class ProjectQueryController {

    private final AiServiceApi aiService;
    private final AiActorProvider actorProvider;

    public ProjectQueryController(AiServiceApi aiService, AiActorProvider actorProvider) {
        this.aiService = aiService;
        this.actorProvider = actorProvider;
    }

    @PostMapping(path = "/project-query", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ProjectQueryAnswer answerProjectQuery(@Valid @RequestBody ProjectQueryRequest request) {
        return aiService.answerProjectQuery(actorProvider.requireUserId(), request.projectId(), request.question());
    }
}
