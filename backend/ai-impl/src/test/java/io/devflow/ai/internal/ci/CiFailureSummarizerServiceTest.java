package io.devflow.ai.internal.ci;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.devflow.ai.internal.config.AiProperties;
import io.devflow.ai.internal.entity.CiSummaryEntity;
import io.devflow.ai.internal.entity.CiSummarySource;
import io.devflow.ai.internal.exception.AiProviderUnavailableException;
import io.devflow.ai.internal.llm.AiChatGateway;
import io.devflow.ai.internal.llm.AiCompletion;
import io.devflow.ai.internal.llm.AiFeature;
import io.devflow.ai.internal.llm.AiMetrics;
import io.devflow.ai.internal.repository.CiSummaryRepository;
import io.devflow.ai.internal.util.LogSanitizer;
import io.devflow.gitci.api.CiFailureInfo;
import io.devflow.gitci.api.GitCiApi;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;

@DisplayName("CiFailureSummarizerService Unit Tests")
class CiFailureSummarizerServiceTest {

    private CiSummaryRepository repository;
    private AiChatGateway chatGateway;
    private LogSanitizer sanitizer;
    private CiLogFetcher logFetcher;
    private CiSummaryValidator validator;
    private AiProperties properties;
    private AiMetrics metrics;
    private GitCiApi gitCiApi;
    private ObjectProvider<GitCiApi> gitCiApiProvider;
    private CiFailureSummarizerService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(CiSummaryRepository.class);
        chatGateway = mock(AiChatGateway.class);
        properties = new AiProperties();
        sanitizer = new LogSanitizer(properties);
        logFetcher = mock(CiLogFetcher.class);
        validator = new CiSummaryValidator(100);
        metrics = mock(AiMetrics.class);
        gitCiApi = mock(GitCiApi.class);
        gitCiApiProvider = mock(ObjectProvider.class);
        when(gitCiApiProvider.getIfAvailable()).thenReturn(gitCiApi);

        service = new CiFailureSummarizerService(
                repository,
                chatGateway,
                sanitizer,
                logFetcher,
                validator,
                properties,
                metrics,
                gitCiApiProvider
        );
    }

    @Test
    void happyPathSummarizesFailureAndSavesEntity() {
        String pipelineId = "pipe-100";
        String rawLog = "Line 1: error in build\nBUILD FAILED";
        String llmSummary = """
                - Build failed due to compilation errors in main module.
                - Error located in App.java line 10.
                - Fix the missing imports and rebuild.
                """.strip();

        when(repository.findByPipelineId(pipelineId)).thenReturn(Optional.empty());
        when(logFetcher.fetch("raw-log-ref")).thenReturn(rawLog);
        when(chatGateway.completeText(eq(AiFeature.CI_SUMMARY), anyString(), anyString(), anyInt(), any()))
                .thenReturn(new AiCompletion(llmSummary, "gpt-4o-mini", 120, 45));

        String result = service.summarize(pipelineId, "raw-log-ref", "devflow/backend", "sha123");

        assertThat(result).isEqualTo(llmSummary);
        ArgumentCaptor<CiSummaryEntity> captor = ArgumentCaptor.forClass(CiSummaryEntity.class);
        verify(repository).save(captor.capture());
        CiSummaryEntity saved = captor.getValue();
        assertThat(saved.getPipelineId()).isEqualTo(pipelineId);
        assertThat(saved.getRepo()).isEqualTo("devflow/backend");
        assertThat(saved.getCommitSha()).isEqualTo("sha123");
        assertThat(saved.getSource()).isEqualTo(CiSummarySource.LLM);
        assertThat(saved.getSummaryMarkdown()).isEqualTo(llmSummary);

        verify(metrics).ciSummary("success");
    }

    @Test
    void idempotentCallReturnsExistingSummaryWithoutInvokingLlm() {
        String pipelineId = "pipe-200";
        CiSummaryEntity existing = new CiSummaryEntity(
                pipelineId, "repo", "sha", "- Existing summary\n- Step 2\n- Step 3", CiSummarySource.LLM);
        when(repository.findByPipelineId(pipelineId)).thenReturn(Optional.of(existing));

        String result = service.summarize(pipelineId, "ref", "repo", "sha");

        assertThat(result).isEqualTo(existing.getSummaryMarkdown());
        verify(chatGateway, never()).completeText(any(), any(), any(), any(), any());
        verify(repository, never()).save(any());
        verify(metrics).ciSummary("skipped_duplicate");
    }

    @Test
    void handlesConcurrentInsertGracefully() {
        String pipelineId = "pipe-300";
        String llmSummary = """
                - Build failed due to timeout.
                - Test execution took longer than 10 minutes.
                - Increase test timeout or optimize slow integration tests.
                """.strip();

        when(repository.findByPipelineId(pipelineId))
                .thenReturn(Optional.empty()) // First check: not found
                .thenReturn(Optional.of(new CiSummaryEntity(pipelineId, "repo", "sha", llmSummary, CiSummarySource.LLM))); // After constraint violation

        when(logFetcher.fetch(any())).thenReturn("some error log");
        when(chatGateway.completeText(any(), any(), any(), any(), any()))
                .thenReturn(new AiCompletion(llmSummary, "gpt-4o-mini", 50, 30));

        when(repository.save(any(CiSummaryEntity.class)))
                .thenThrow(new DataIntegrityViolationException("Unique constraint violation"));

        String result = service.summarize(pipelineId, "ref", "repo", "sha");

        assertThat(result).isEqualTo(llmSummary);
        verify(metrics).ciSummary("skipped_duplicate");
    }

    @Test
    void fallsBackToStandardSummaryWhenLlmThrows() {
        String pipelineId = "pipe-400";
        when(repository.findByPipelineId(pipelineId)).thenReturn(Optional.empty());
        when(logFetcher.fetch(any())).thenReturn("Fatal error occurred in runner");
        when(chatGateway.completeText(any(), any(), any(), any(), any()))
                .thenThrow(new AiProviderUnavailableException(30, new RuntimeException("OpenAI connection timeout")));

        String result = service.summarize(pipelineId, "ref", "repo", "sha");

        assertThat(result).isEqualTo(CiFailureSummarizerService.FALLBACK_SUMMARY);

        ArgumentCaptor<CiSummaryEntity> captor = ArgumentCaptor.forClass(CiSummaryEntity.class);
        verify(repository).save(captor.capture());
        CiSummaryEntity saved = captor.getValue();
        assertThat(saved.getSource()).isEqualTo(CiSummarySource.FALLBACK);
        assertThat(saved.getSummaryMarkdown()).isEqualTo(CiFailureSummarizerService.FALLBACK_SUMMARY);

        verify(metrics).ciSummary("fallback");
    }

    @Test
    void summarizeFromApiLooksUpGitCiApiAndSummarizes() {
        String pipelineId = "pipe-500";
        CiFailureInfo failureInfo = new CiFailureInfo(pipelineId, "my-repo", "commit-abc", "storage-url", Instant.now());
        when(repository.findByPipelineId(pipelineId)).thenReturn(Optional.empty());
        when(gitCiApi.findFailure(pipelineId)).thenReturn(Optional.of(failureInfo));
        when(logFetcher.fetch("storage-url")).thenReturn("Compiling...\nError on line 5");

        String llmSummary = "- Failed compilation\n- In line 5\n- Fix syntax";
        when(chatGateway.completeText(any(), any(), any(), any(), any()))
                .thenReturn(new AiCompletion(llmSummary, "gpt-4o-mini", 60, 20));

        String result = service.summarizeFromApi(pipelineId);

        assertThat(result).isEqualTo(llmSummary);
        verify(gitCiApi).findFailure(pipelineId);
    }

    @Test
    void summarizeFromApiReturnsNotFoundWhenFailureInfoIsEmpty() {
        String pipelineId = "unknown-pipe";
        when(repository.findByPipelineId(pipelineId)).thenReturn(Optional.empty());
        when(gitCiApi.findFailure(pipelineId)).thenReturn(Optional.empty());

        String result = service.summarizeFromApi(pipelineId);

        assertThat(result).contains("CI failure not found for pipeline unknown-pipe");
        verify(repository, never()).save(any());
    }
}
