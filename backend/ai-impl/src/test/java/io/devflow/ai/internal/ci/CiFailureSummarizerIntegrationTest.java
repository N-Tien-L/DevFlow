package io.devflow.ai.internal.ci;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.devflow.ai.internal.TestAiApplication;
import io.devflow.ai.internal.entity.CiSummaryEntity;
import io.devflow.ai.internal.entity.CiSummarySource;
import io.devflow.ai.internal.repository.CiSummaryRepository;
import io.devflow.common.event.CiFailureDetectedEvent;
import io.devflow.common.event.CiFailureSummarizedEvent;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

@SpringBootTest(classes = TestAiApplication.class)
@RecordApplicationEvents
@DisplayName("CI Failure Summarizer Event & Persistence Integration Test")
class CiFailureSummarizerIntegrationTest {

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private CiSummaryRepository repository;

    @Autowired
    private ApplicationEvents applicationEvents;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    @Test
    void consumesCiFailureDetectedAndPublishesSummarizedEvent() {
        String pipelineId = "pipe-int-001";
        String rawLog = """
                [INFO] Building my-service 1.0.0
                [DEBUG] Database connecting to postgresql://devflow:superSecretPassword@localhost:5432/db
                [ERROR] Failed to execute goal compileJava: package io.devflow.missing does not exist
                [ERROR] Line 42 in /app/src/main/java/Main.java
                [INFO] BUILD FAILED
                """;

        eventPublisher.publishEvent(new CiFailureDetectedEvent(
                pipelineId, rawLog, "org/repo", "commit-sha-12345"));

        // Await asynchronous processing by AiCiExecutor
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Optional<CiSummaryEntity> entity = repository.findByPipelineId(pipelineId);
            assertThat(entity).isPresent();
            assertThat(entity.get().getPipelineId()).isEqualTo(pipelineId);
            assertThat(entity.get().getRepo()).isEqualTo("org/repo");
            assertThat(entity.get().getCommitSha()).isEqualTo("commit-sha-12345");
            assertThat(entity.get().getSource()).isEqualTo(CiSummarySource.LLM);

            // Summary must have exactly 3 bullets
            String summary = entity.get().getSummaryMarkdown();
            assertThat(summary.lines().count()).isEqualTo(3);
            assertThat(summary).doesNotContain("superSecretPassword");

            // Verify CiFailureSummarizedEvent published
            long eventCount = applicationEvents.stream(CiFailureSummarizedEvent.class)
                    .filter(e -> pipelineId.equals(e.pipelineId()))
                    .count();
            assertThat(eventCount).isEqualTo(1);
        });
    }

    @Test
    void duplicateEventIsIdempotentAndDoesNotCreateDuplicateRow() {
        String pipelineId = "pipe-int-002";
        String rawLog = "[ERROR] Build failed due to test failure";

        // Publish first time
        eventPublisher.publishEvent(new CiFailureDetectedEvent(
                pipelineId, rawLog, "org/repo", "commit-sha-2"));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(repository.findByPipelineId(pipelineId)).isPresent()
        );

        String initialSummary = repository.findByPipelineId(pipelineId).get().getSummaryMarkdown();

        // Publish second time with different log (should be ignored due to idempotency)
        eventPublisher.publishEvent(new CiFailureDetectedEvent(
                pipelineId, "different log content", "org/repo", "commit-sha-2"));

        // Wait a short duration to ensure duplicate event processing completes
        try {
            Thread.sleep(500);
        } catch (InterruptedException ignored) {}

        assertThat(repository.count()).isEqualTo(1);
        assertThat(repository.findByPipelineId(pipelineId).get().getSummaryMarkdown()).isEqualTo(initialSummary);

        long eventCount = applicationEvents.stream(CiFailureSummarizedEvent.class)
                .filter(e -> pipelineId.equals(e.pipelineId()))
                .count();
        assertThat(eventCount).isEqualTo(1);
    }
}
