package io.devflow.notification.internal;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class NotificationCleanup {
    private final NotificationRepository repository;
    private final NotificationProperties properties;
    private final TransactionTemplate transaction;

    public NotificationCleanup(NotificationRepository repository, NotificationProperties properties,
            PlatformTransactionManager manager) {
        this.repository = repository;
        this.properties = properties;
        this.transaction = new TransactionTemplate(manager);
    }

    @Scheduled(cron = "${devflow.notification.cleanup-cron:0 0 3 * * *}", zone = "UTC")
    public void purge() {
        Instant cutoff = Instant.now().minus(properties.getRetentionDays(), ChronoUnit.DAYS);
        try {
            for (int batch = 0; batch < properties.getCleanupMaxBatches(); batch++) {
                Integer deleted = transaction.execute(status -> repository.deleteBatch(cutoff, properties.getCleanupBatchSize()));
                if (deleted == null || deleted < properties.getCleanupBatchSize()) { break; }
            }
        } catch (RuntimeException exception) {
            org.slf4j.LoggerFactory.getLogger(getClass()).error("Notification cleanup failed exceptionType={}",
                    exception.getClass().getSimpleName());
        }
    }
}
