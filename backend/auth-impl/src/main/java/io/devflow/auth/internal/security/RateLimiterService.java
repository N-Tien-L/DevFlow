package io.devflow.auth.internal.security;

import io.devflow.auth.internal.exception.RateLimitExceededException;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * In-memory Token Bucket rate limiting service using Bucket4j.
 * Protects sensitive endpoints (e.g. login) against brute-force attacks.
 */
@Component
public class RateLimiterService {

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final long capacity;
    private final Duration refillDuration;

    public RateLimiterService() {
        // Default: 5 requests per minute per key
        this(5, Duration.ofMinutes(1));
    }

    public RateLimiterService(long capacity, Duration refillDuration) {
        this.capacity = capacity;
        this.refillDuration = refillDuration;
    }

    /**
     * Attempts to consume 1 token for the given client key (e.g. IP address or email).
     *
     * @param key unique client identifier
     * @throws RateLimitExceededException if rate limit is exhausted
     */
    public void checkRateLimit(String key) {
        Bucket bucket = buckets.computeIfAbsent(key, k -> createNewBucket());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            long waitSeconds = TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill());
            if (waitSeconds < 1) {
                waitSeconds = 1;
            }
            throw new RateLimitExceededException(
                    "Too many requests. Rate limit exceeded. Please try again later.",
                    waitSeconds
            );
        }
    }

    /**
     * Resets tokens for a given key (used in testing or when a client successfully authenticates).
     */
    public void reset(String key) {
        buckets.remove(key);
    }

    /**
     * Clears all active buckets.
     */
    public void clearAll() {
        buckets.clear();
    }

    private Bucket createNewBucket() {
        Bandwidth limit = Bandwidth.builder()
                .capacity(capacity)
                .refillIntervally(capacity, refillDuration)
                .build();
        return Bucket.builder().addLimit(limit).build();
    }
}
