package io.devflow.notification.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.devflow.auth.api.AuthApi;
import io.devflow.board.api.BoardApi;
import io.devflow.common.event.CiFailureSummarizedEvent;
import io.devflow.common.event.DevFlowEvent;
import io.devflow.common.event.GitPrMergedEvent;
import io.devflow.common.event.GitPrOpenedEvent;
import io.devflow.common.event.RiskDeadlineFlaggedEvent;
import io.devflow.common.event.TaskCreatedEvent;
import io.devflow.common.event.TaskStatusChangedEvent;
import io.devflow.common.security.AuthenticatedActor;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Only assignees are currently resolvable through the public Board API. */
@Component
public class NotificationEventListeners {
    private static final Logger log = LoggerFactory.getLogger(NotificationEventListeners.class);
    private final BoardApi board;
    private final AuthApi auth;
    private final NotificationService service;
    private final ObjectMapper mapper;
    private final MeterRegistry metrics;

    public NotificationEventListeners(BoardApi board, AuthApi auth, NotificationService service,
            ObjectMapper mapper, MeterRegistry metrics) {
        this.board = board;
        this.auth = auth;
        this.service = service;
        this.mapper = mapper;
        this.metrics = metrics;
    }

    // fallbackExecution supports the Board publisher that already publishes AFTER_COMMIT,
    // and nontransactional system producers. Delivery always starts its own transaction.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(TaskCreatedEvent event) {
        consume(event, event.taskId(), event.eventId(), event.actorId(), "Task assigned",
                "A task has been assigned to you.");
    }
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(TaskStatusChangedEvent event) {
        consume(event, event.taskId(), event.eventId(), event.actorId(), "Task status changed",
                "The status of your task has changed.");
    }
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(GitPrOpenedEvent event) {
        consume(event, event.taskId(), null, null, "Pull request opened",
                "A pull request linked to your task has been opened.");
    }
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(GitPrMergedEvent event) {
        consume(event, event.taskId(), null, null, "Pull request merged",
                "A pull request linked to your task has been merged.");
    }
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(CiFailureSummarizedEvent event) {
        consume(event, event.relatedTaskId(), null, null, "CI failure analyzed",
                "A CI failure linked to your task has been analyzed. Open the task to review it.");
    }
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(RiskDeadlineFlaggedEvent event) {
        consume(event, event.taskId(), null, null, "Deadline at risk",
                "Your task has a deadline risk. Open the task to review it.");
    }

    private void consume(DevFlowEvent event, UUID taskId, UUID eventId, UUID actorId, String title, String content) {
        String outcome = "skipped";
        try {
            if (taskId == null) { return; }
            var task = board.findTask(taskId);
            if (task.isEmpty()) { return; }
            var summary = task.get();
            UUID recipient = summary.assigneeId();
            UUID actor = actorId == null ? currentActor() : actorId;
            if (recipient == null || recipient.equals(actor)
                    || !auth.isWorkspaceMember(recipient, summary.projectId())) { return; }
            UUID stableId = eventId == null ? legacyEventId(event) : eventId;
            service.deliver(recipient, stableId, summary.projectId(), taskId, event.eventType(), title, content);
            outcome = "success";
        } catch (RuntimeException exception) {
            outcome = "failure";
            // Never log event payloads, content, exception messages, or user identities.
            log.error("Notification event consumer failed type={} exceptionType={}",
                    event.eventType(), exception.getClass().getSimpleName());
        } finally {
            try {
                metrics.counter("devflow.task.events.consume", "eventType", event.eventType(),
                        "consumer", "notification", "outcome", outcome).increment();
            } catch (RuntimeException exception) {
                log.warn("Notification consumer metric unavailable type={}", event.eventType());
            }
        }
    }

    private UUID legacyEventId(DevFlowEvent event) {
        try {
            // Includes event class/type and occurredAt; identical replays yield the same id.
            byte[] payload = mapper.writeValueAsBytes(event);
            byte[] prefix = event.eventType().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] key = new byte[prefix.length + 1 + payload.length];
            System.arraycopy(prefix, 0, key, 0, prefix.length);
            System.arraycopy(payload, 0, key, prefix.length + 1, payload.length);
            return UUID.nameUUIDFromBytes(key);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot identify legacy notification event");
        }
    }

    private static UUID currentActor() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof AuthenticatedActor actor ? actor.userId() : null;
    }
}
