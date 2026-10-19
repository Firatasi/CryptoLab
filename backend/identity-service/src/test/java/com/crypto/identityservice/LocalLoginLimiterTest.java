package com.crypto.identityservice;

import com.crypto.identityservice.infrastructure.ratelimit.LocalLoginLimiter;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LocalLoginLimiterTest {

    @Test
    void shouldAllowFirstTenAttemptsAndRejectEleventh() {
        var clock = new MutableClock(
                Instant.parse("2026-10-19T10:00:00Z")
        );
        var limiter = new LocalLoginLimiter(clock);

        for (int i = 0; i < 10; i++) {
            assertDoesNotThrow(() -> limiter.check("ip:127.0.0.1"));
        }

        var exception = assertThrows(
                IllegalStateException.class,
                () -> limiter.check("ip:127.0.0.1")
        );

        assertEquals("RATE_LIMITED", exception.getMessage());
    }

    @Test
    void shouldKeepLimitsIndependentForDifferentKeys() {
        var clock = new MutableClock(
                Instant.parse("2026-10-19T10:00:00Z")
        );
        var limiter = new LocalLoginLimiter(clock);

        for (int i = 0; i < 10; i++) {
            limiter.check("email:first");
        }

        assertThrows(
                IllegalStateException.class,
                () -> limiter.check("email:first")
        );

        for (int i = 0; i < 10; i++) {
            assertDoesNotThrow(() -> limiter.check("email:second"));
        }

        assertThrows(
                IllegalStateException.class,
                () -> limiter.check("email:second")
        );
    }

    @Test
    void shouldResetLimitAtStartOfNextMinute() {
        var clock = new MutableClock(
                Instant.parse("2026-10-19T10:00:00Z")
        );
        var limiter = new LocalLoginLimiter(clock);

        for (int i = 0; i < 10; i++) {
            limiter.check("ip:127.0.0.1");
        }

        // Aynı dakikanın son saniyesinde hâlâ engelli.
        clock.set(Instant.parse("2026-10-19T10:00:59Z"));

        assertThrows(
                IllegalStateException.class,
                () -> limiter.check("ip:127.0.0.1")
        );

        // Yeni dakika başladığında 10 deneme yeniden kullanılabilir.
        clock.set(Instant.parse("2026-10-19T10:01:00Z"));

        for (int i = 0; i < 10; i++) {
            assertDoesNotThrow(() -> limiter.check("ip:127.0.0.1"));
        }

        assertThrows(
                IllegalStateException.class,
                () -> limiter.check("ip:127.0.0.1")
        );
    }

    private static final class MutableClock extends Clock {

        private final AtomicReference<Instant> current;
        private final ZoneId zone;

        private MutableClock(Instant initial) {
            this(new AtomicReference<>(initial), ZoneOffset.UTC);
        }

        private MutableClock(
                AtomicReference<Instant> current,
                ZoneId zone
        ) {
            this.current = current;
            this.zone = zone;
        }

        void set(Instant instant) {
            current.set(instant);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(current, zone);
        }

        @Override
        public Instant instant() {
            return current.get();
        }
    }
}