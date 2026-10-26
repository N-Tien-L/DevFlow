package io.devflow.notification.internal;

import io.devflow.notification.api.NotificationApi;
import io.devflow.notification.api.NotificationPage;
import io.devflow.notification.api.NotificationResponse;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class NotificationService implements NotificationApi {
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private final NotificationRepository repository;
    private final SimpMessagingTemplate messaging;
    private final MeterRegistry metrics;
    private final TransactionTemplate deliveryTransaction;

    public NotificationService(NotificationRepository repository, SimpMessagingTemplate messaging,
            MeterRegistry metrics, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.messaging = messaging;
        this.metrics = metrics;
        deliveryTransaction = new TransactionTemplate(transactionManager);
        deliveryTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Commit independently of the completed publisher transaction before pushing. */
    public void deliver(UUID recipientId, UUID eventId, UUID workspaceId, UUID taskId,
            String type, String title, String content) {
        Objects.requireNonNull(recipientId);
        Objects.requireNonNull(eventId);
        NotificationResponse notification = new NotificationResponse(UUID.randomUUID(), eventId, workspaceId,
                type, plainText(title, 255), plainText(content, 4000), taskId, false, Instant.now(), null);
        boolean inserted = Boolean.TRUE.equals(deliveryTransaction.execute(status -> repository.insert(recipientId, notification)));
        if (!inserted) {
            return;
        }
        recordMetric("devflow.notification.delivered", type);
        try {
            messaging.convertAndSendToUser(recipientId.toString(), "/queue/notifications", notification);
        } catch (RuntimeException exception) {
            log.warn("Notification realtime delivery failed type={} exceptionType={}", type,
                    exception.getClass().getSimpleName());
            recordMetric("devflow.notification.realtime.failed", type);
        }
    }

    @Override
    public void sendToUser(UUID userId, String subject, String body) {
        deliver(userId, UUID.randomUUID(), null, null, "system", subject, body);
    }

    @Transactional(readOnly = true)
    public NotificationPage list(UUID userId, int page, int size) {
        if (page < 0 || page > 10000 || size < 1 || size > 100) {
            throw new NotificationException(400, "Invalid notification pagination.");
        }
        var rows = repository.list(userId, page, size);
        return new NotificationPage(rows.stream().limit(size).toList(), page, size, rows.size() > size);
    }

    @Transactional(readOnly = true)
    public long unreadCount(UUID userId) { return repository.unreadCount(userId); }

    @Transactional
    public NotificationResponse markRead(UUID userId, UUID id) {
        repository.markRead(userId, id, Instant.now());
        return repository.find(userId, id).orElseThrow(() -> new NotificationException(404, "Notification not found."));
    }

    @Transactional
    public int markAllRead(UUID userId) { return repository.markAllRead(userId, Instant.now()); }

    private static String plainText(String value, int maxLength) {
        String cleaned = Objects.requireNonNull(value).replaceAll("<[^>]*>", "").trim();
        if (cleaned.isBlank() || cleaned.length() > maxLength) {
            throw new IllegalArgumentException("Invalid notification text");
        }
        return cleaned;
    }

    private void recordMetric(String name, String type) {
        try {
            metrics.counter(name, "type", type).increment();
        } catch (RuntimeException exception) {
            log.warn("Notification metric unavailable type={}", type);
        }
    }
}
