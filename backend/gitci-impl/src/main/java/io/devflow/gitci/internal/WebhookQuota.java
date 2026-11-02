package io.devflow.gitci.internal;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded token buckets; untrusted forwarded-address headers are never used. */
final class WebhookQuota {
    private final Map<String, Bucket> peers = new LinkedHashMap<>(128, .75f, true);
    private final Bucket global = new Bucket(60);

    synchronized boolean allow(String peer) {
        long now = System.nanoTime();
        peers.entrySet().removeIf(e -> now - e.getValue().lastSeen > 900_000_000_000L);
        String key = peer == null ? "unknown" : peer;
        Bucket bucket = peers.get(key);
        if (bucket == null) {
            if (peers.size() >= 10000) return false;
            bucket = new Bucket(100);
            peers.put(key, bucket);
        }
        global.refill(now, 60, 30.0);
        bucket.refill(now, 100, 5.0);
        if (global.tokens < 1 || bucket.tokens < 1) return false;
        global.tokens--;
        bucket.tokens--;
        return true;
    }

    private static class Bucket {
        double tokens;
        long lastSeen = System.nanoTime();
        Bucket(int capacity) { tokens = capacity; }
        void refill(long now, int capacity, double rate) {
            tokens = Math.min(capacity, tokens + Math.max(0, now - lastSeen) / 1_000_000_000.0 * rate);
            lastSeen = now;
        }
    }
}
