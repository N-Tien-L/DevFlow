package io.devflow.auth.internal.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.devflow.auth.internal.exception.RateLimitExceededException;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * In-memory Token Bucket rate limiting service using Bucket4j.
 * Each named policy from {@link RateLimitProperties} keeps its own buckets per client key.
 * Idle buckets are evicted so the key space cannot grow without bound.
 */
@Component
public class RateLimiterService {

    private static final long MAX_TRACKED_KEYS = 100_000;

    private final RateLimitProperties properties;
    private final Cache<String, Bucket> buckets;

    public RateLimiterService(RateLimitProperties properties) {
        this.properties = properties;
        Duration longestPeriod = properties.getPolicies().values().stream()
                .map(RateLimitProperties.Policy::getPeriod)
                .max(Duration::compareTo)
                .orElse(Duration.ofHours(1));
        this.buckets = Caffeine.newBuilder()
                .maximumSize(MAX_TRACKED_KEYS)
                .expireAfterAccess(longestPeriod)
                .build();
    }

    /**
     * Attempts to consume 1 token for the given client key (e.g. IP address or email) under a policy.
     *
     * @param policyName policy name declared in {@link RateLimitProperties}
     * @param key        unique client identifier
     * @throws RateLimitExceededException if rate limit is exhausted
     */
    public void checkRateLimit(String policyName, String key) {
        RateLimitProperties.Policy policy = properties.policy(policyName);
        Bucket bucket = buckets.get(policyName + ":" + key, k -> createNewBucket(policy));
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            long waitSeconds = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()));
            throw new RateLimitExceededException(
                    "Too many requests. Rate limit exceeded. Please try again later.",
                    waitSeconds
            );
        }
    }

    /**
     * Clears all active buckets (used in tests).
     */
    public void clearAll() {
        buckets.invalidateAll();
    }

    private Bucket createNewBucket(RateLimitProperties.Policy policy) {
        Bandwidth limit = Bandwidth.builder()
                .capacity(policy.getCapacity())
                .refillIntervally(policy.getCapacity(), policy.getPeriod())
                .build();
        return Bucket.builder().addLimit(limit).build();
    }
}
