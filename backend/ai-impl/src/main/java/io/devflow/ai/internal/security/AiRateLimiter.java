package io.devflow.ai.internal.security;

import io.devflow.ai.internal.config.AiProperties;
import io.devflow.ai.internal.exception.AiApiException;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Per-user token bucket shared by every AI feature: LLM calls cost money, so the limit follows the
 * user, not the endpoint. In-memory, which matches the single-instance deployment (as Board's limiter).
 */
@Component
public class AiRateLimiter {

    private final Map<UUID, BucketState> buckets = new ConcurrentHashMap<>();
    private final long capacity;
    private final Duration period;
    private final AtomicLong requests = new AtomicLong();
    private final MeterRegistry registry;

    public AiRateLimiter(AiProperties properties, ObjectProvider<MeterRegistry> registryProvider) {
        this.capacity = properties.getRateLimit().getCapacity();
        this.period = properties.getRateLimit().getPeriod();
        this.registry = registryProvider.getIfAvailable(SimpleMeterRegistry::new);
    }

    /**
     * @param feature metric tag of the AI feature consuming the token
     * @throws AiApiException 429 with Retry-After once the user's bucket is empty
     */
    public void check(UUID userId, String feature) {
        long now = System.nanoTime();
        BucketState state = buckets.computeIfAbsent(userId, ignored -> new BucketState(newBucket(), now));
        state.lastAccessNanos = now;
        if (requests.incrementAndGet() % 256 == 0) {
            evictIdleBuckets(now);
        }

        ConsumptionProbe probe = state.bucket.tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            registry.counter("devflow.ai.rate_limited", "feature", feature).increment();
            long retryAfter = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()));
            throw AiApiException.rateLimited(retryAfter);
        }
    }

    private Bucket newBucket() {
        Bandwidth limit = Bandwidth.builder()
                .capacity(capacity)
                .refillIntervally(capacity, period)
                .build();
        return Bucket.builder().addLimit(limit).build();
    }

    private void evictIdleBuckets(long nowNanos) {
        long idleThreshold = period.multipliedBy(2).toNanos();
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
