package com.crypto.simulationservice.application.replay;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * Sorumluluk: Tarihsel çalışmanın yürütülmesini yönetir.
 * Okuma ipucu: Sanal saat ve deterministik sıra gerçek sistem saatinden bağımsız tutulur.
 */
public final class VirtualClock extends Clock {

    private Instant current;

    public VirtualClock(Instant start) {
        this.current = Objects.requireNonNull(start);
    }

    public void advanceTo(Instant next) {
        if (next.isBefore(current)) {
            throw new IllegalArgumentException("CLOCK_MOVED_BACKWARD");
        }
        current = next;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        if (!ZoneOffset.UTC.equals(zone)) {
            throw new IllegalArgumentException("UTC_ONLY");
        }
        return this;
    }

    @Override
    public Instant instant() {
        return current;
    }
}

