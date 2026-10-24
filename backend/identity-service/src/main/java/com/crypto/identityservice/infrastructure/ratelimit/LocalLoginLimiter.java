package com.crypto.identityservice.infrastructure.ratelimit;

import com.crypto.identityservice.application.port.LoginLimiter;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.LinkedHashMap;

@Component
public class LocalLoginLimiter implements LoginLimiter {

    private record Window(long minute, int count) {
    }

    private final LinkedHashMap<String, Window> buckets =
            new LinkedHashMap<>();

    private final Clock clock;

    public LocalLoginLimiter(Clock clock) {
        this.clock = clock;
    }

    @Override
    public synchronized void check(String key) {
        long minute = clock.instant().getEpochSecond() / 60;

        // Önceki dakikalara ait sayaçları kaldır.
        buckets.entrySet()
                .removeIf(entry -> entry.getValue().minute() < minute);

        // Belleğin sınırsız büyümesini engelle.
        if (!buckets.containsKey(key) && buckets.size() >= 10_000) {
            throw new IllegalStateException("RATE_LIMITED");
        }

        Window previous = buckets.getOrDefault(
                key,
                new Window(minute, 0)
        );

        if (previous.count() >= 10) {
            throw new IllegalStateException("RATE_LIMITED");
        }

        buckets.put(
                key,
                new Window(minute, previous.count() + 1)
        );
    }
}
