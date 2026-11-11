package io.devflow.ai.internal.ci;

import io.devflow.ai.internal.config.AiProperties;
import io.devflow.ai.internal.entity.CiSummaryEntity;
import io.devflow.ai.internal.entity.CiSummarySource;
import io.devflow.ai.internal.llm.AiChatGateway;
import io.devflow.ai.internal.llm.AiCompletion;
import io.devflow.ai.internal.llm.AiFeature;
import io.devflow.ai.internal.llm.AiMetrics;
import io.devflow.ai.internal.repository.CiSummaryRepository;
import io.devflow.ai.internal.util.LogSanitizer;
import io.devflow.gitci.api.CiFailureInfo;
import io.devflow.gitci.api.GitCiApi;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Core service for summarizing CI pipeline failure logs using the LLM (T-031).
 *
 * <p>Enforces:
 * <ul>
 *   <li>Secret redacting on raw logs before sending to the model</li>
 *   <li>Log truncation around failure/error zones to stay within token limits</li>
 *   <li>Validation of 3 bullet points under 100 words</li>
 *   <li>Graceful fallback to standard failure summary if the LLM provider fails</li>
 *   <li>Strict idempotency per pipeline id in {@link CiSummaryRepository}</li>
 *   <li>Observability via Micrometer metrics</li>
 * </ul>
 */
@Service
public class CiFailureSummarizerService {

    public static final String FALLBACK_SUMMARY = """
            - CI pipeline failed. Automated summary unavailable.
            - Please review the raw log for details.
            - Contact the DevOps team if the issue persists.
            """.strip();

    private static final Logger log = LoggerFactory.getLogger(CiFailureSummarizerService.class);

    private final CiSummaryRepository repository;
    private final AiChatGateway chatGateway;
    private final LogSanitizer sanitizer;
    private final CiLogFetcher logFetcher;
    private final CiSummaryValidator validator;
    private final AiProperties properties;
    private final AiMetrics metrics;
    private final ObjectProvider<GitCiApi> gitCiApiProvider;

    public CiFailureSummarizerService(
            CiSummaryRepository repository,
            AiChatGateway chatGateway,
            LogSanitizer sanitizer,
            CiLogFetcher logFetcher,
            CiSummaryValidator validator,
            AiProperties properties,
            AiMetrics metrics,
            ObjectProvider<GitCiApi> gitCiApiProvider) {
        this.repository = repository;
        this.chatGateway = chatGateway;
        this.sanitizer = sanitizer;
        this.logFetcher = logFetcher;
        this.validator = validator;
        this.properties = properties;
        this.metrics = metrics;
        this.gitCiApiProvider = gitCiApiProvider;
    }

    /**
     * Summarizes a CI pipeline failure log. Guaranteed to be idempotent per {@code pipelineId}.
     *
     * @param pipelineId      unique identifier of the failed pipeline
     * @param rawLogReference reference (URL, path, or text) to the raw CI log
     * @param repo            source repository name
     * @param commitSha       failing commit SHA
     * @return 3-bullet markdown summary
     */
    public String summarize(String pipelineId, String rawLogReference, String repo, String commitSha) {
        if (!StringUtils.hasText(pipelineId)) {
            throw new IllegalArgumentException("pipelineId must not be blank");
        }

        // 1. Idempotency check: return existing summary if already processed
        Optional<CiSummaryEntity> existing = repository.findByPipelineId(pipelineId);
        if (existing.isPresent()) {
            log.info("CI failure summary already exists for pipeline={}, returning cached result", pipelineId);
            metrics.ciSummary("skipped_duplicate");
            return existing.get().getSummaryMarkdown();
        }

        // 2. Fetch raw log
        String rawLog = logFetcher.fetch(rawLogReference);

        // 3. Redact secrets
        String sanitized = sanitizer.sanitize(rawLog);

        // 4. Truncate around error lines
        int maxLogSizeKb = properties.getCiSummary().getMaxLogSizeKb();
        String truncatedLog = sanitizer.truncateAroundError(sanitized, maxLogSizeKb);

        // 5. Invoke LLM with validation and fallback
        String summaryMarkdown;
        CiSummarySource source;
        String metricResult;

        try {
            String userPrompt = CiSummaryPrompts.user(pipelineId, repo, commitSha, truncatedLog);
            AiCompletion completion = chatGateway.completeText(
                    AiFeature.CI_SUMMARY,
                    CiSummaryPrompts.SYSTEM,
                    userPrompt,
                    properties.getCiSummary().getMaxOutputTokens(),
                    validator::validate
            );
            summaryMarkdown = completion.text();
            source = CiSummarySource.LLM;
            metricResult = "success";
        } catch (Exception e) {
            log.warn("LLM CI summary generation failed for pipeline={}: {}", pipelineId, e.getMessage());
            summaryMarkdown = FALLBACK_SUMMARY;
            source = CiSummarySource.FALLBACK;
            metricResult = "fallback";
        }

        // 6. Persist summary with concurrency protection
        return persistSummary(pipelineId, repo, commitSha, summaryMarkdown, source, metricResult);
    }

    /**
     * Look up CI failure details via {@link GitCiApi} and summarize.
     */
    public String summarizeFromApi(String pipelineId) {
        if (!StringUtils.hasText(pipelineId)) {
            throw new IllegalArgumentException("pipelineId must not be blank");
        }

        Optional<CiSummaryEntity> existing = repository.findByPipelineId(pipelineId);
        if (existing.isPresent()) {
            return existing.get().getSummaryMarkdown();
        }

        GitCiApi gitCiApi = gitCiApiProvider.getIfAvailable();
        if (gitCiApi == null) {
            log.warn("GitCiApi is not available to look up pipelineId={}", pipelineId);
            return summarize(pipelineId, "", null, null);
        }

        Optional<CiFailureInfo> failure;
        try {
            failure = gitCiApi.findFailure(pipelineId);
        } catch (Exception e) {
            log.warn("GitCiApi.findFailure failed for pipelineId={}: {}", pipelineId, e.getMessage());
            failure = Optional.empty();
        }

        if (failure.isEmpty()) {
            log.warn("No CI failure found via GitCiApi for pipelineId={}", pipelineId);
            return "CI failure not found for pipeline " + pipelineId + ".";
        }

        CiFailureInfo info = failure.get();
        return summarize(info.pipelineId(), info.rawLogReference(), info.repo(), info.commitSha());
    }

    private String persistSummary(String pipelineId, String repo, String commitSha,
            String summaryMarkdown, CiSummarySource source, String metricResult) {
        try {
            CiSummaryEntity entity = new CiSummaryEntity(pipelineId, repo, commitSha, summaryMarkdown, source);
            repository.save(entity);
            metrics.ciSummary(metricResult);
            return summaryMarkdown;
        } catch (DataIntegrityViolationException concurrent) {
            log.info("Concurrent insert detected for pipeline={}, returning existing entry", pipelineId);
            metrics.ciSummary("skipped_duplicate");
            return repository.findByPipelineId(pipelineId)
                    .map(CiSummaryEntity::getSummaryMarkdown)
                    .orElse(summaryMarkdown);
        }
    }
}
