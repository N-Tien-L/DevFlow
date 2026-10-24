package io.devflow.notification.internal;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("devflow.notification")
public class NotificationProperties {
    @Min(1) @Max(3650)
    private int retentionDays = 90;
    @Min(1) @Max(10000)
    private int cleanupBatchSize = 1000;
    @Min(1) @Max(100)
    private int cleanupMaxBatches = 10;
    @Min(1) @Max(10000)
    private int rateLimitPerMinute = 120;

    public int getRetentionDays() { return retentionDays; }
    public void setRetentionDays(int value) { retentionDays = value; }
    public int getCleanupBatchSize() { return cleanupBatchSize; }
    public void setCleanupBatchSize(int value) { cleanupBatchSize = value; }
    public int getCleanupMaxBatches() { return cleanupMaxBatches; }
    public void setCleanupMaxBatches(int value) { cleanupMaxBatches = value; }
    public int getRateLimitPerMinute() { return rateLimitPerMinute; }
    public void setRateLimitPerMinute(int value) { rateLimitPerMinute = value; }
}
