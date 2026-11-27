package io.devflow.board.internal;

import io.devflow.common.event.EventTypes;
import io.devflow.common.event.GitCommitLinkedEvent;
import io.devflow.common.event.GitPrMergedEvent;
import io.devflow.common.event.GitPrOpenedEvent;
import io.devflow.common.event.TaskGitActivityChangedEvent.ActivityKind;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Synchronous adapters: transient failures escape to GitCI's bounded retry queue; permanent skips do not block sibling listeners. */
@Component
public class GitActivityEventListener {
    private static final Logger log = LoggerFactory.getLogger(GitActivityEventListener.class);
    private static final Set<String> SKIPPED_REASONS = Set.of(
            "DISABLED", "INVALID_EVENT", "TENANT_MISMATCH", "SKIPPED_TARGET_MISSING", "SKIPPED_TARGET_AMBIGUOUS");
    private static final Set<String> BADGE_REFRESH_REASONS = Set.of(
            "NOOP", "DISABLED", "STALE_MANUAL", "ARCHIVED", "SKIPPED_TARGET_MISSING", "SKIPPED_TARGET_AMBIGUOUS");
    private final GitTaskTransitionService transitions;
    private final MeterRegistry metrics;
    private final BoardGitActivityRealtimeListener activityRealtime;

    public GitActivityEventListener(GitTaskTransitionService transitions, MeterRegistry metrics) {
        this(transitions, metrics, null);
    }

    @Autowired
    GitActivityEventListener(GitTaskTransitionService transitions, MeterRegistry metrics,
                             BoardGitActivityRealtimeListener activityRealtime) {
        this.transitions = transitions;
        this.metrics = metrics;
        this.activityRealtime = activityRealtime;
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
        String eventType = event.eventType() == null ? "unknown" : event.eventType();
        long start = System.nanoTime();
        try {
            MDC.clear();
            MDC.put(BoardRequestContext.CORRELATION_ID_KEY, UUID.randomUUID().toString());
            MDC.put("sourceEventId", String.valueOf(event.eventId()));
            // Keep context alive through the transactional proxy's commit and afterCommit dispatch.
            reason = transitions.apply(event);
            outcome = SKIPPED_REASONS.contains(reason) ? "SKIPPED" : "SUCCESS";
            if (activityRealtime != null && BADGE_REFRESH_REASONS.contains(reason)
                    && event.workspaceId() != null && event.taskId() != null) {
                String kind = EventTypes.GIT_COMMIT_LINKED.equals(eventType)
                        ? ActivityKind.COMMIT_LINKED.name()
                        : ActivityKind.PR_UPDATED.name();
                activityRealtime.broadcastForTasks(event.workspaceId(), Set.of(event.taskId()), kind, event.eventId());
            }
        } catch (RuntimeException exception) {
            if (exception instanceof GitTransitionException rejected) reason = rejected.reason();
            throw exception;
        } finally {
            log.info("Board Git transition eventId={} taskId={} type={} outcome={} reason={}",
                    event.eventId(), event.taskId(), eventType, outcome, reason);
            try {
                metrics.counter("devflow.board.git.transitions", "eventType", eventType,
                        "outcome", outcome, "reason", reason).increment();
                metrics.timer("devflow.board.git.transition.duration", "eventType", eventType)
                        .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            } catch (RuntimeException ignored) {
                log.warn("Board Git transition metric unavailable");
            }
            if (previous == null) MDC.clear(); else MDC.setContextMap(previous);
        }
    }
}
