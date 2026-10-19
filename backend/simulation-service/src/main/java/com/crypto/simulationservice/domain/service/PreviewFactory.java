package com.crypto.simulationservice.domain.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.crypto.financecore.finance.Decimal;
import com.crypto.financecore.finance.Finance;
import com.crypto.financecore.finance.MaintenanceTier;
import com.crypto.financecore.finance.PositionSide;
import com.crypto.simulationservice.domain.model.ExecutionPlan;

public final class PreviewFactory {

    public record Rule(UUID id, BigDecimal tick, BigDecimal step, BigDecimal minNotional, BigDecimal takerRate,
            List<MaintenanceTier> tiers) {}

    public record Request(UUID session, UUID instrument, long instrumentVersion, long generation, String side, String orderType,
            BigDecimal marginBudget, BigDecimal leverage, BigDecimal limit, BigDecimal stop, BigDecimal target, int horizon, String expiryPolicy) {}

    public record Context(BigDecimal bid, BigDecimal ask, BigDecimal available, BigDecimal slipBps, long riskVersion,
            long ttlSeconds, BigDecimal lower, BigDecimal upper, BigDecimal maxFee, boolean requireStop, boolean requireTarget) {}

    private PreviewFactory() {
    }

    public static ExecutionPlan create(Request r, Context c, Rule rule, Instant now) {
        if (!Set.of(60, 900, 3600, 86400, 604800).contains(r.horizon())) {
            throw new IllegalArgumentException("INVALID_HORIZON");
        }
        if (!Set.of("REEVALUATE", "CLOSE_AT_DEADLINE").contains(r.expiryPolicy())) {
            throw new IllegalArgumentException("INVALID_EXPIRY_POLICY");
        }
        if (!Set.of("MARKET", "LIMIT").contains(r.orderType())) {
            throw new IllegalArgumentException("INVALID_ORDER_TYPE");
        }
        if (c.ttlSeconds() <= 0) {
            throw new IllegalArgumentException("INVALID_TTL");
        }
        boolean buy = "LONG".equals(r.side());
        PositionSide side = PositionSide.valueOf(r.side());
        if (("LIMIT".equals(r.orderType())) != (r.limit() != null)) {
            throw new IllegalArgumentException("LIMIT_PRICE_REQUIRED_OR_FORBIDDEN");
        }
        BigDecimal reference = r.limit() == null ? Finance.marketPrice(buy, c.bid(), c.ask(), rule.tick(), c.slipBps()) : Decimal.positive(r.limit());
        if (r.limit() != null && r.limit().remainder(rule.tick()).signum() != 0) {
            throw new IllegalArgumentException("INVALID_TICK");
        }
        var open = Finance.open(r.marginBudget(), r.leverage(), reference, rule.step(), rule.takerRate(), c.available());
        if (open.notional().compareTo(rule.minNotional()) < 0) {
            throw new IllegalArgumentException("MIN_NOTIONAL");
        }
        ExecutionGate.bracket(r.side(), reference, r.stop(), r.target(), c.requireStop(), c.requireTarget());
        if (c.lower().signum() <= 0 || c.lower().compareTo(reference) > 0 || c.upper().compareTo(reference) < 0 || c.maxFee().compareTo(open.fee()) < 0) {
            throw new IllegalArgumentException("INVALID_APPROVAL_BOUNDS");
        }
        return new ExecutionPlan(UUID.randomUUID(), 1, null, r.session(), r.instrument(), r.instrumentVersion(), "MANUAL", null, r.generation(),
                r.side(), r.orderType(), open.quantity(), reference, r.limit(), open.margin(), open.fee(), open.notional(), r.leverage(), r.stop(), r.target(),
                r.horizon(), r.expiryPolicy(), c.lower(), c.upper(), c.maxFee(), c.slipBps(), rule.id(), c.riskVersion(), now, now.plusSeconds(c.ttlSeconds()),
                Finance.liquidation(side, open.quantity(), reference, open.margin(), rule.tiers()).orElse(null), List.of());
    }
}
