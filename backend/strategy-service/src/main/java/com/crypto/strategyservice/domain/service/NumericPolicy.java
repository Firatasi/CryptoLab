package com.crypto.strategyservice.domain.service;

import com.crypto.strategyservice.domain.model.PredictionResult;

import java.math.BigDecimal;
import java.math.MathContext;

public final class NumericPolicy {

    public record Artifact(
            String version,
            double minNetEdge,
            double minDataCoverage,
            BigDecimal stopAtrMultiplier,
            BigDecimal targetRiskReward,
            BigDecimal suggestedLeverage,
            double maxVolatility
    ) {
    }

    public record Outcome(
            String action,
            BigDecimal stopLoss,
            BigDecimal takeProfit,
            BigDecimal suggestedLeverage,
            String reason
    ) {
    }

    private NumericPolicy() {
    }

    public static Outcome evaluate(
            PredictionResult result,
            Artifact policy,
            BigDecimal reference,
            BigDecimal verifiedAtr,
            double coverage,
            double estimatedRoundTripCost
    ) {
        if (result.quality() != PredictionResult.Quality.READY) {
            return waitOutcome(result.quality().name());
        }

        if (!Double.isFinite(coverage)
                || !Double.isFinite(estimatedRoundTripCost)
                || estimatedRoundTripCost < 0) {
            throw new IllegalArgumentException("INVALID_POLICY_INPUT");
        }

        if (coverage < policy.minDataCoverage()
                || result.volatilityEstimate() > policy.maxVolatility()) {
            return waitOutcome("POLICY_GATE");
        }

        double edge = Math.expm1(result.predictedLogReturn());

        if (!Double.isFinite(edge)) {
            throw new IllegalArgumentException("INVALID_EDGE");
        }

        if (Math.abs(edge) - estimatedRoundTripCost < policy.minNetEdge()) {
            return waitOutcome("INSUFFICIENT_EDGE");
        }

        if (verifiedAtr.signum() <= 0
                || reference.signum() <= 0
                || policy.stopAtrMultiplier().signum() <= 0
                || policy.targetRiskReward().signum() <= 0) {
            throw new IllegalArgumentException("INVALID_POLICY_PRICE");
        }

        BigDecimal stopDistance = verifiedAtr.multiply(
                policy.stopAtrMultiplier(),
                MathContext.DECIMAL128
        );

        BigDecimal targetDistance = stopDistance.multiply(
                policy.targetRiskReward(),
                MathContext.DECIMAL128
        );

        boolean longSide = edge > 0;

        BigDecimal stop = longSide
                ? reference.subtract(stopDistance)
                : reference.add(stopDistance);

        BigDecimal target = longSide
                ? reference.add(targetDistance)
                : reference.subtract(targetDistance);

        if (stop.signum() <= 0 || target.signum() <= 0) {
            return waitOutcome("INVALID_PRICE_LEVEL");
        }

        return new Outcome(
                longSide ? "LONG" : "SHORT",
                stop,
                target,
                policy.suggestedLeverage(),
                "NUMERIC_POLICY"
        );
    }

    private static Outcome waitOutcome(String reason) {
        return new Outcome("WAIT", null, null, null, reason);
    }
}