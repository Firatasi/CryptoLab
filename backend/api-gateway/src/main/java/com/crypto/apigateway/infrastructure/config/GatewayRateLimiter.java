package com.crypto.apigateway.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Ağ geçidi seviyesinde bellek sınırlı sliding-window hız sınırlayıcı.
 */
@Component
public class GatewayRateLimiter {

    private static final int MAX_TRACKED_BUCKETS = 20_000;
    private static final long WINDOW_MS = 60_000L;

    private final boolean enabled;
    private final int authPerMinute;
    private final int generalPerMinute;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public GatewayRateLimiter(
            @Value("${app.gateway.rate-limit.enabled:true}") boolean enabled,
            @Value("${app.gateway.rate-limit.auth-per-minute:60}") int authPerMinute,
            @Value("${app.gateway.rate-limit.general-per-minute:600}") int generalPerMinute
    ) {
        this.enabled = enabled;
        this.authPerMinute = Math.max(10, authPerMinute);
        this.generalPerMinute = Math.max(30, generalPerMinute);
    }

    public boolean allow(String clientIp, String uri) {
        if (!enabled || uri == null || uri.startsWith("/actuator")) {
            return true;
        }
        boolean isAuth = uri.startsWith("/api/v1/auth/");
        boolean isSse = uri.endsWith("/stream");
        if (isSse) {
            return true;
        }
        int limit = isAuth ? authPerMinute : generalPerMinute;
        String bucketKey = (isAuth ? "auth:" : "api:") + clientIp;
        long now = System.currentTimeMillis();
        if (buckets.size() > MAX_TRACKED_BUCKETS) {
            buckets.entrySet().removeIf(entry -> now - entry.getValue().windowStart > WINDOW_MS);
            if (buckets.size() > MAX_TRACKED_BUCKETS) {
                buckets.clear();
            }
        }
        Bucket bucket = buckets.compute(bucketKey, (k, existing) -> {
            if (existing == null || now - existing.windowStart >= WINDOW_MS) {
                return new Bucket(now);
            }
            return existing;
        });
        return bucket.count.incrementAndGet() <= limit;
    }

    private static final class Bucket {
        private final long windowStart;
        private final AtomicInteger count = new AtomicInteger(0);

        private Bucket(long windowStart) {
            this.windowStart = windowStart;
        }
    }
}
