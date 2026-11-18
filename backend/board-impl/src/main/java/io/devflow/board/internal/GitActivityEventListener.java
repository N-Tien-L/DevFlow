package io.devflow.board.internal;

import io.devflow.common.event.GitCommitLinkedEvent;
import io.devflow.common.event.GitPrMergedEvent;
import io.devflow.common.event.GitPrOpenedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Synchronous adapters: the worker observes transaction failure and retries the same event ID. */
@Component
public class GitActivityEventListener {
    private static final Logger log = LoggerFactory.getLogger(GitActivityEventListener.class);
    private final GitTaskTransitionService transitions;
    private final MeterRegistry metrics;

    public GitActivityEventListener(GitTaskTransitionService transitions, MeterRegistry metrics) {
        this.transitions = transitions;
        this.metrics = metrics;
    }

    @EventListener
    public void on(GitCommitLinkedEvent event) {
        handle(new GitTaskTransition(event.eventId(), event.repositoryId(), event.workspaceId(), event.taskId(),
                event.eventType(), event.occurredAt(), event.commitSha(), event.repo(), event.author(), null));
    }

    @EventListener
    public void on(GitPrOpenedEvent event) {
        handle(new GitTaskTransition(event.eventId(), event.repositoryId(), event.workspaceId(), event.taskId(),
                event.eventType(), event.occurredAt(), event.prUrl(), event.repo(), null, event.actorId()));
    }

    @EventListener
    public void on(GitPrMergedEvent event) {
        handle(new GitTaskTransition(event.eventId(), event.repositoryId(), event.workspaceId(), event.taskId(),
                event.eventType(), event.occurredAt(), event.prUrl(), event.repo(), null, event.actorId()));
    }

    private void handle(GitTaskTransition event) {
        var previous = MDC.getCopyOfContextMap();
        String outcome = "ERROR";
        String reason = "DATABASE_OR_CONSUMER";
        long start = System.nanoTime();
        try {
            MDC.clear();
            MDC.put(BoardRequestContext.CORRELATION_ID_KEY, UUID.randomUUID().toString());
            MDC.put("sourceEventId", String.valueOf(event.eventId()));
            // Keep context alive through the transactional proxy's commit and afterCommit dispatch.
            reason = transitions.apply(event);
            outcome = "SUCCESS";
        } catch (RuntimeException exception) {
            if (exception instanceof GitTransitionException rejected) reason = rejected.reason();
            throw exception;
        } finally {
            log.info("Board Git transition eventId={} taskId={} type={} outcome={} reason={}",
                    event.eventId(), event.taskId(), event.eventType(), outcome, reason);
            try {
                metrics.counter("devflow.board.git.transitions", "eventType", event.eventType(),
                        "outcome", outcome, "reason", reason).increment();
                metrics.timer("devflow.board.git.transition.duration", "eventType", event.eventType())
                        .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            } catch (RuntimeException ignored) {
                log.warn("Board Git transition metric unavailable");
            }
            if (previous == null) MDC.clear(); else MDC.setContextMap(previous);
        }
    }
}
