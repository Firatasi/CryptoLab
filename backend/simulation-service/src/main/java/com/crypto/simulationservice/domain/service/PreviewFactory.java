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
import com.crypto.simulationservice.application.port.ProposalReader;
import com.crypto.simulationservice.domain.exception.BusinessException;
import com.crypto.simulationservice.domain.model.ExecutionPlan;

import static com.crypto.financecore.finance.Decimal.MC;

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
        return createInternal("MANUAL", null, r, c, rule, now);
    }

    public static ExecutionPlan createFromModifiedProposal(UUID originProposalId, Request r, Context c, Rule rule, Instant now) {
        if (originProposalId == null) {
            throw new IllegalArgumentException("ORIGIN_PROPOSAL_REQUIRED");
        }
        return createInternal("MANUAL", originProposalId, r, c, rule, now);
    }

    private static ExecutionPlan createInternal(String source, UUID proposalId, Request r, Context c, Rule rule, Instant now) {
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
        return new ExecutionPlan(UUID.randomUUID(), 1, null, r.session(), r.instrument(), r.instrumentVersion(), source, proposalId, r.generation(),
                r.side(), r.orderType(), open.quantity(), reference, r.limit(), open.margin(), open.fee(), open.notional(), r.leverage(), r.stop(), r.target(),
                r.horizon(), r.expiryPolicy(), c.lower(), c.upper(), c.maxFee(), c.slipBps(), rule.id(), c.riskVersion(), now, now.plusSeconds(c.ttlSeconds()),
                Finance.liquidation(side, open.quantity(), reference, open.margin(), rule.tiers()).orElse(null), List.of());
    }

    public static ExecutionPlan createFromAiProposal(
            ProposalReader.ProposalSnapshot proposal,
            long instrumentVersion,
            long currentGeneration,
            BigDecimal equity,
            BigDecimal existingNotional,
            BigDecimal unitCostBuffer,
            RiskPolicyEvaluator.Limits limits,
            Context c,
            Rule rule,
            Instant now
    ) {
        if (proposal == null || !"READY".equals(proposal.state())) {
            throw new BusinessException(BusinessException.Code.PLAN_REVOKED);
        }
        if (!now.isBefore(proposal.validUntil())) {
            throw new BusinessException(BusinessException.Code.PLAN_EXPIRED);
        }
        if (currentGeneration != proposal.generation()) {
            throw new BusinessException(BusinessException.Code.PLAN_REVOKED);
        }
        // AI önerisinde SL ve TP zorunludur.
        if (proposal.stopLoss() == null || proposal.takeProfit() == null) {
            throw new IllegalArgumentException("AI_PROPOSAL_BRACKET_REQUIRED");
        }
        if (!Set.of(60, 900, 3600, 86400, 604800).contains(proposal.horizonSeconds())) {
            throw new IllegalArgumentException("INVALID_HORIZON");
        }
        if (!Set.of("REEVALUATE", "CLOSE_AT_DEADLINE").contains(proposal.expiryPolicy())) {
            throw new IllegalArgumentException("INVALID_EXPIRY_POLICY");
        }
        if (!Set.of("MARKET", "LIMIT").contains(proposal.orderType())) {
            throw new IllegalArgumentException("INVALID_ORDER_TYPE");
        }
        if (c.ttlSeconds() <= 0) {
            throw new IllegalArgumentException("INVALID_TTL");
        }
        if (("LIMIT".equals(proposal.orderType())) != (proposal.limitPrice() != null)) {
            throw new IllegalArgumentException("LIMIT_PRICE_REQUIRED_OR_FORBIDDEN");
        }

        boolean buy = "LONG".equals(proposal.side());
        PositionSide side = PositionSide.valueOf(proposal.side());
        BigDecimal reference = proposal.limitPrice() == null
                ? Finance.marketPrice(buy, c.bid(), c.ask(), rule.tick(), c.slipBps())
                : Decimal.positive(proposal.limitPrice());
        if (proposal.limitPrice() != null && proposal.limitPrice().remainder(rule.tick()).signum() != 0) {
            throw new IllegalArgumentException("INVALID_TICK");
        }

        // AI önerisinde hem Stop-Loss hem Take-Profit zorunludur (requireStop=true, requireTarget=true).
        ExecutionGate.bracket(proposal.side(), reference, proposal.stopLoss(), proposal.takeProfit(), true, true);

        BigDecimal leverage = proposal.suggestedLeverage().min(limits.maxLeverage());
        // Miktar, marjin ve komisyon otoritesi yalnız Java finans/risk motorundadır.
        BigDecimal quantity = RiskPolicyEvaluator.size(
                reference,
                proposal.stopLoss(),
                leverage,
                equity,
                c.available(),
                existingNotional,
                limits.maxMarginPerTrade(),
                rule.takerRate(),
                unitCostBuffer,
                rule.step(),
                limits
        );
        if (quantity.signum() <= 0) {
            throw new IllegalArgumentException("INSUFFICIENT_RISK_CAPACITY");
        }

        BigDecimal notional = quantity.multiply(reference, MC);
        if (notional.compareTo(rule.minNotional()) < 0) {
            throw new IllegalArgumentException("MIN_NOTIONAL");
        }
        BigDecimal margin = notional.divide(leverage, MC);
        BigDecimal fee = Finance.fee(quantity, reference, rule.takerRate());
        if (margin.add(fee).compareTo(c.available()) > 0) {
            throw new IllegalArgumentException("INSUFFICIENT_AVAILABLE_BALANCE");
        }
        if (c.lower().signum() <= 0 || c.lower().compareTo(reference) > 0
                || c.upper().compareTo(reference) < 0 || c.maxFee().compareTo(fee) < 0) {
            throw new IllegalArgumentException("INVALID_APPROVAL_BOUNDS");
        }

        Instant standardExpiry = now.plusSeconds(c.ttlSeconds());
        Instant effectiveValidUntil = standardExpiry.isBefore(proposal.validUntil())
                ? standardExpiry
                : proposal.validUntil();

        return new ExecutionPlan(
                UUID.randomUUID(),
                1,
                null,
                proposal.sessionId(),
                proposal.instrumentId(),
                instrumentVersion,
                "AI_PROPOSAL",
                proposal.proposalId(),
                currentGeneration,
                proposal.side(),
                proposal.orderType(),
                quantity,
                reference,
                proposal.limitPrice(),
                margin,
                fee,
                notional,
                leverage,
                proposal.stopLoss(),
                proposal.takeProfit(),
                proposal.horizonSeconds(),
                proposal.expiryPolicy(),
                c.lower(),
                c.upper(),
                c.maxFee(),
                c.slipBps(),
                rule.id(),
                c.riskVersion(),
                now,
                effectiveValidUntil,
                Finance.liquidation(side, quantity, reference, margin, rule.tiers()).orElse(null),
                List.of()
        );
    }
}
