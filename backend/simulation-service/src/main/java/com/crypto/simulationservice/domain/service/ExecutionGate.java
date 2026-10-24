package com.crypto.simulationservice.domain.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import com.crypto.simulationservice.domain.model.ExecutionPlan;

public final class ExecutionGate {

    private ExecutionGate() {
    }

    public static void fresh(Instant source, Instant received, Instant now, long maxAgeMs, long skewMs, String code) {
        if (source.isAfter(now.plusMillis(skewMs)) || received.isAfter(now.plusMillis(skewMs))
                || Duration.between(source, now).toMillis() > maxAgeMs || Duration.between(received, now).toMillis() > maxAgeMs) {
            throw new IllegalStateException(code);
        }
    }

    public static void bracket(String side, BigDecimal entry, BigDecimal stop, BigDecimal target, boolean requireStop, boolean requireTarget) {
        if ((requireStop && stop == null) || (requireTarget && target == null)) {
            throw new IllegalArgumentException("BRACKET_REQUIRED");
        }
        boolean longSide = "LONG".equals(side);
        if (!longSide && !"SHORT".equals(side)) {
            throw new IllegalArgumentException("INVALID_SIDE");
        }
        if (stop != null && (stop.signum() <= 0 || (longSide ? stop.compareTo(entry) >= 0 : stop.compareTo(entry) <= 0))) {
            throw new IllegalArgumentException("INVALID_STOP");
        }
        if (target != null && (target.signum() <= 0 || (longSide ? target.compareTo(entry) <= 0 : target.compareTo(entry) >= 0))) {
            throw new IllegalArgumentException("INVALID_TARGET");
        }
    }

    public static void approved(ExecutionPlan p, Instant now, long generation, BigDecimal fill, BigDecimal fee) {
        if (!now.isBefore(p.validUntil())) {
            throw new IllegalStateException("PLAN_EXPIRED");
        }
        if (generation != p.positionGeneration()) {
            throw new IllegalStateException("PLAN_REVOKED");
        }
        if (fill.compareTo(p.priceLowerBound()) < 0 || fill.compareTo(p.priceUpperBound()) > 0 || fee.compareTo(p.maxOpenFee()) > 0) {
            throw new IllegalStateException("REVALIDATION_REQUIRED");
        }
        bracket(p.side(), fill, p.stopLoss(), p.takeProfit(), "AI_PROPOSAL".equals(p.source()), "AI_PROPOSAL".equals(p.source()));
    }
}
