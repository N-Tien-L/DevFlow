package io.devflow.ai.internal;

import io.devflow.ai.internal.ci.AiCiExecutor;
import io.devflow.ai.internal.ci.CiFailureSummarizerService;
import io.devflow.common.event.CiFailureDetectedEvent;
import io.devflow.common.event.CiFailureSummarizedEvent;
import io.devflow.common.event.TaskCreatedEvent;
import io.devflow.common.event.TaskStatusChangedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * AI service's subscriptions on the event bus (ARCHITECTURE.md Section 4).
 *
 * <p>Listens for {@code ci.failure_detected} and asynchronously summarizes the failure
 * off the webhook thread, then publishes {@code ci.failure_summarized}.
 */
@Component
public class AiEventListeners {

    private static final Logger log = LoggerFactory.getLogger(AiEventListeners.class);
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;
    private final CiFailureSummarizerService ciFailureSummarizerService;
    private final ApplicationEventPublisher eventPublisher;
    private final AiCiExecutor aiExecutor;

    public AiEventListeners(
            ObjectProvider<MeterRegistry> meterRegistryProvider,
            CiFailureSummarizerService ciFailureSummarizerService,
            ApplicationEventPublisher eventPublisher,
            AiCiExecutor aiExecutor) {
        this.meterRegistryProvider = meterRegistryProvider;
        this.ciFailureSummarizerService = ciFailureSummarizerService;
        this.eventPublisher = eventPublisher;
        this.aiExecutor = aiExecutor;
    }

    @EventListener
    public void on(CiFailureDetectedEvent event) {
        log.info("[{}] pipeline={} repo={} sha={}",
                event.eventType(), event.pipelineId(), event.repo(), event.commitSha());
        boolean submitted = aiExecutor.submit(() -> {
            try {
                CiFailureSummarizerService.CiSummaryResult result = ciFailureSummarizerService.summarizeForEvent(
                        event.pipelineId(), event.rawLogReference(), event.repo(), event.commitSha());
                if (result.newlyCreated() && result.summaryMarkdown() != null) {
                    eventPublisher.publishEvent(new CiFailureSummarizedEvent(
                            event.pipelineId(), result.summaryMarkdown(), null, Instant.now()));
                }
                recordConsumeMetric(event.eventType(), "success");
            } catch (Exception e) {
                log.error("Failed to summarize CI failure for pipeline={}", event.pipelineId(), e);
                recordConsumeMetric(event.eventType(), "failure");
            }
        });
        if (!submitted) {
            log.warn("Rejected CI failure summarization task (executor saturated) pipeline={}", event.pipelineId());
            recordConsumeMetric(event.eventType(), "rejected");
        }
    }

    @EventListener
    public void on(TaskCreatedEvent event) {
        consume(event.eventType(), event.eventId(), event.taskId(), () -> {
            log.info("[{}] eventId={} task={} board={} workspace={} column={} actor={}",
                    event.eventType(), event.eventId(), event.taskId(), event.boardId(), event.projectId(),
                    event.columnId(), event.actorId());
            // TODO(PRODUCT_SPEC 5.2b): feed into deadline-risk tracking.
        });
    }

    @EventListener
    public void on(TaskStatusChangedEvent event) {
        consume(event.eventType(), event.eventId(), event.taskId(), () -> {
            log.info("[{}] eventId={} task={} board={} workspace={} {} -> {} category={} -> {} cause={} "
                            + "correlationId={} actorId={}",
                    event.eventType(), event.eventId(), event.taskId(), event.boardId(), event.workspaceId(),
                    event.sourceColumnId(), event.destinationColumnId(), event.oldStatusCategory(),
                    event.newStatusCategory(), event.cause(), event.correlationId(), event.actorId());
            // TODO(PRODUCT_SPEC 5.2b): update completion pace; publish RiskDeadlineFlaggedEvent
            //   when pace vs. plan indicates risk.
        });
    }

    private void consume(String eventType, java.util.UUID eventId, java.util.UUID taskId, Runnable action) {
        String outcome = "success";
        try {
            action.run();
        } catch (RuntimeException exception) {
            outcome = "failure";
            log.error("AI task event handler failed type={} eventId={} taskId={} exceptionType={}",
                    eventType, eventId, taskId, exception.getClass().getSimpleName());
        } finally {
            recordConsumeMetric(eventType, outcome);
        }
    }

    private void recordConsumeMetric(String eventType, String outcome) {
        try {
            MeterRegistry registry = meterRegistryProvider.getIfAvailable();
            if (registry != null) {
                registry.counter("devflow.task.events.consume",
                        "eventType", eventType, "consumer", "ai", "outcome", outcome).increment();
            }
        } catch (RuntimeException exception) {
            log.warn("AI task event metric failed type={} exceptionType={}",
                    eventType, exception.getClass().getSimpleName());
        }
    }
}
