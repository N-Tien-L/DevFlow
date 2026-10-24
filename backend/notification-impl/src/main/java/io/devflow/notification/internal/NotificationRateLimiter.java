package io.devflow.notification.internal;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/** Bounded per-user token buckets for the single-instance deployment. */
@Component
public class NotificationRateLimiter {
    private final Map<UUID, State> buckets = new ConcurrentHashMap<>();
    private final NotificationProperties properties;
    private long requests;
    public NotificationRateLimiter(NotificationProperties properties) { this.properties = properties; }

    public synchronized void check(UUID userId) {
        long now = System.nanoTime();
        if (++requests % 256 == 0 || buckets.size() >= 10000) {
            buckets.entrySet().removeIf(entry -> now - entry.getValue().lastAccess > Duration.ofMinutes(2).toNanos());
        }
        if (!buckets.containsKey(userId) && buckets.size() >= 10000) {
            throw new NotificationException(429, "Notification rate limit reached.", 60L);
        }
        State state = buckets.computeIfAbsent(userId, ignored -> new State(Bucket.builder().addLimit(
                Bandwidth.builder().capacity(properties.getRateLimitPerMinute())
                        .refillGreedy(properties.getRateLimitPerMinute(), Duration.ofMinutes(1)).build()).build()));
        state.lastAccess = now;
        var probe = state.bucket.tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            long seconds = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()) + 1);
            throw new NotificationException(429, "Notification rate limit reached.", seconds);
        }
    }
    private static class State {
        private final Bucket bucket;
        private long lastAccess;
        State(Bucket bucket) { this.bucket = bucket; }
    }
}
