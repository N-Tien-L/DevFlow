package io.devflow.board.internal;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Per-actor token bucket for authenticated Board mutations (single instance). */
@Component
public class BoardRateLimiter {

    private final Map<UUID, BucketState> buckets = new ConcurrentHashMap<>();
    private final long capacity;
    private final Duration refillDuration;
    private final AtomicLong requests = new AtomicLong();

    public BoardRateLimiter(
            @Value("${devflow.board.rate-limit.capacity:60}") long capacity,
            @Value("${devflow.board.rate-limit.refill-seconds:60}") long refillSeconds) {
        if (capacity < 1 || refillSeconds < 1) {
            throw new IllegalArgumentException("Board rate limit settings must be positive");
        }
        this.capacity = capacity;
        this.refillDuration = Duration.ofSeconds(refillSeconds);
    }

    public void check(UUID actorId) {
        long now = System.nanoTime();
        BucketState state = buckets.computeIfAbsent(actorId, ignored -> new BucketState(newBucket(), now));
        state.lastAccessNanos = now;
        if (requests.incrementAndGet() % 256 == 0) {
            evictIdleBuckets(now);
        }

        ConsumptionProbe probe = state.bucket.tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            long retryAfter = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()));
            throw BoardApiException.rateLimited(retryAfter);
        }
    }

    private Bucket newBucket() {
        Bandwidth limit = Bandwidth.builder()
                .capacity(capacity)
                .refillIntervally(capacity, refillDuration)
                .build();
        return Bucket.builder().addLimit(limit).build();
    }

    private void evictIdleBuckets(long nowNanos) {
        long idleThreshold = refillDuration.multipliedBy(2).toNanos();
        buckets.entrySet().removeIf(entry -> nowNanos - entry.getValue().lastAccessNanos > idleThreshold);
    }

    private static final class BucketState {
        private final Bucket bucket;
        private volatile long lastAccessNanos;

        private BucketState(Bucket bucket, long lastAccessNanos) {
            this.bucket = bucket;
            this.lastAccessNanos = lastAccessNanos;
        }
    }
}
