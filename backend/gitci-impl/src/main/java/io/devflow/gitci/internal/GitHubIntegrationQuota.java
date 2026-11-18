package io.devflow.gitci.internal;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Only authenticated, configured repository IDs reach this bounded map. */
final class GitHubIntegrationQuota {
    private final Map<UUID, Bucket> buckets = new HashMap<>();

    synchronized boolean allow(UUID repositoryId) {
        long now = System.nanoTime();
        Bucket bucket = buckets.computeIfAbsent(repositoryId, ignored -> new Bucket(now));
        bucket.tokens = Math.min(60, bucket.tokens + Math.max(0, now - bucket.last) / 1_000_000_000.0 * 2);
        bucket.last = now;
        if (bucket.tokens < 1) return false;
        bucket.tokens--;
        return true;
    }

    private static final class Bucket {
        double tokens = 60;
        long last;
        Bucket(long now) { last = now; }
    }
}
