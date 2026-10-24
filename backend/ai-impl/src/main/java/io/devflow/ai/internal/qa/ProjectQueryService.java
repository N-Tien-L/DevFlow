package io.devflow.ai.internal.qa;

import io.devflow.ai.api.ProjectQueryAnswer;
import io.devflow.ai.api.ProjectQueryRequest;
import io.devflow.ai.internal.config.AiProperties;
import io.devflow.ai.internal.exception.AiApiException;
import io.devflow.ai.internal.llm.AiChatGateway;
import io.devflow.ai.internal.llm.AiCompletion;
import io.devflow.ai.internal.llm.AiFeature;
import io.devflow.ai.internal.llm.AiMalformedOutputException;
import io.devflow.ai.internal.security.AiRateLimiter;
import io.devflow.ai.internal.security.WorkspaceMembershipChecker;
import io.devflow.auth.api.AuthApi;
import io.devflow.auth.api.UserSummary;
import io.devflow.board.api.BoardApi;
import io.devflow.board.api.TaskSummary;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Answers natural-language questions about a project from its board data only (T-033). A project id is a
 * workspace id (Board's {@code BoardApi} contract), so access is workspace membership.
 *
 * <p>Order of checks: input validation (400), per-user AI rate limit (429), workspace membership (403);
 * only then is board data read and the model called. {@code BoardApi} does no permission check of its own,
 * so the membership check must stay before {@link BoardApi#findTasksByProject}. An empty or oversized
 * answer is asked for again up to {@code devflow.ai.qa.max-output-attempts} times in total, then fails
 * with 502. Questions and answers are never logged, only their lengths.
 */
@Service
public class ProjectQueryService {

    static final int MAX_ANSWER_CHARS = 4000;

    private static final Logger log = LoggerFactory.getLogger(ProjectQueryService.class);
    // Control characters except tab and newline (CR dropped; CRLF becomes LF).
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\n\\t]]");

    private final AiChatGateway gateway;
    private final AiRateLimiter rateLimiter;
    private final WorkspaceMembershipChecker membership;
    private final BoardApi boardApi;
    private final AuthApi authApi;
    private final AiProperties properties;
    private final ProjectSnapshotBuilder snapshotBuilder;
    private final Clock clock;

    public ProjectQueryService(AiChatGateway gateway, AiRateLimiter rateLimiter,
            WorkspaceMembershipChecker membership, BoardApi boardApi, AuthApi authApi, AiProperties properties,
            ObjectProvider<Clock> clock) {
        this.gateway = gateway;
        this.rateLimiter = rateLimiter;
        this.membership = membership;
        this.boardApi = boardApi;
        this.authApi = authApi;
        this.properties = properties;
        this.snapshotBuilder = new ProjectSnapshotBuilder(properties.getQa());
        this.clock = clock.getIfUnique(Clock::systemUTC);
    }

    public ProjectQueryAnswer answer(UUID userId, UUID projectId, String rawQuestion) {
        if (userId == null) {
            throw AiApiException.authenticationRequired();
        }
        if (projectId == null) {
            throw AiApiException.badRequest("PROJECT_REQUIRED", "projectId is required.");
        }
        String question = sanitize(rawQuestion);
        if (question.isEmpty()) {
            throw AiApiException.badRequest("AI_QUESTION_REQUIRED", "Question is required.");
        }
        if (question.length() > ProjectQueryRequest.MAX_QUESTION_LENGTH) {
            throw AiApiException.badRequest("AI_QUESTION_TOO_LONG", "Question must not exceed "
                    + ProjectQueryRequest.MAX_QUESTION_LENGTH + " characters.");
        }
        rateLimiter.check(userId, AiFeature.PROJECT_QA.tag());
        membership.requireMember(userId, projectId);

        List<TaskSummary> tasks = loadTasks(projectId);
        // Room left for the data block once the instructions, question and boundary lines are counted.
        int dataBudget = properties.getChat().getMaxPromptChars() - ProjectQueryPrompts.SYSTEM.length()
                - ProjectQueryPrompts.framingLength() - question.length();
        ProjectSnapshotBuilder.Built data = snapshotBuilder.build(tasks, this::displayName, userId,
                LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC), dataBudget);
        String userPrompt = ProjectQueryPrompts.user(data.json(), question);

        int attempts = properties.getQa().getMaxOutputAttempts();
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                AiCompletion completion = gateway.completeText(AiFeature.PROJECT_QA, ProjectQueryPrompts.SYSTEM,
                        userPrompt, properties.getQa().getMaxOutputTokens(), ProjectQueryService::normalizeAnswer);
                log.info("Project question answered userId={} projectId={} questionChars={} totalTasks={} "
                                + "tasksListed={} answerChars={} attempt={} model={}",
                        userId, projectId, question.length(), data.totalTasks(), data.tasksListed(),
                        completion.text().length(), attempt, completion.model());
                return new ProjectQueryAnswer(completion.text(), data.totalTasks(), data.tasksListed());
            } catch (AiMalformedOutputException malformed) {
                log.warn("Project answer unusable userId={} projectId={} attempt={}/{}",
                        userId, projectId, attempt, attempts);
            }
        }
        throw AiApiException.outputInvalid();
    }

    private List<TaskSummary> loadTasks(UUID projectId) {
        try {
            return Objects.requireNonNullElse(boardApi.findTasksByProject(projectId), List.of());
        } catch (RuntimeException failure) {
            log.error("Project tasks could not be loaded projectId={} exceptionType={}",
                    projectId, failure.getClass().getSimpleName());
            throw AiApiException.projectDataUnavailable();
        }
    }

    /** Display name only: e-mail addresses never reach the model. */
    private String displayName(UUID userId) {
        try {
            return authApi.findUser(userId).map(UserSummary::displayName).orElse(null);
        } catch (RuntimeException failure) {
            log.warn("Assignee name lookup failed exceptionType={}", failure.getClass().getSimpleName());
            return null;
        }
    }

    static String normalizeAnswer(String answer) {
        String cleaned = CONTROL.matcher(answer).replaceAll("").strip();
        if (cleaned.isEmpty() || cleaned.length() > MAX_ANSWER_CHARS) {
            throw new AiMalformedOutputException("answer length outside 1-" + MAX_ANSWER_CHARS);
        }
        return cleaned;
    }

    private static String sanitize(String question) {
        return CONTROL.matcher(Objects.requireNonNullElse(question, "")).replaceAll("").strip();
    }
}
