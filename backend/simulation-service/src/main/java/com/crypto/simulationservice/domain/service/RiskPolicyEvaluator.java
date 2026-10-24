package com.crypto.simulationservice.domain.service;

import com.crypto.financecore.finance.Decimal;
import com.crypto.financecore.finance.Finance;
import java.math.BigDecimal;
import static com.crypto.financecore.finance.Decimal.MC;

/**
 * Sorumluluk: Girdilerden iş kararı/hesap üretir. Okuma ipucu: Aynı girdinin
 * aynı sonucu üretmesi beklenir; DB/ağ yan etkisi ekleme.
 */
public final class RiskPolicyEvaluator {

    public record Limits(BigDecimal maxLeverage, int maxPositions, BigDecimal maxPositionNotional,
            BigDecimal maxTotalNotional, BigDecimal maxMarginPerTrade, BigDecimal riskFraction, BigDecimal maxDailyLossFraction) {

        public Limits {
            Decimal.positive(maxLeverage);
            Decimal.positive(maxPositionNotional);
            Decimal.positive(maxTotalNotional);
            Decimal.positive(maxMarginPerTrade);
            if (maxLeverage.compareTo(BigDecimal.ONE) < 0 || maxPositions < 1 || riskFraction.signum() <= 0 || riskFraction.compareTo(BigDecimal.ONE) > 0
                    || maxDailyLossFraction.signum() <= 0 || maxDailyLossFraction.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("INVALID_RISK_POLICY");
            }
        }
    }

    private RiskPolicyEvaluator() {
    }

    public static BigDecimal size(BigDecimal entry, BigDecimal stop, BigDecimal leverage, BigDecimal equity,
            BigDecimal available, BigDecimal existingNotional, BigDecimal marginBudget, BigDecimal feeRate,
            BigDecimal unitCostBuffer, BigDecimal step, Limits limits) {
        if (leverage.compareTo(BigDecimal.ONE) < 0 || leverage.compareTo(limits.maxLeverage()) > 0) {
            throw new IllegalArgumentException("LEVERAGE_LIMIT");
        }
        Decimal.positive(entry);
        Decimal.nonNegative(unitCostBuffer);
        BigDecimal budget = marginBudget.min(limits.maxMarginPerTrade());
        BigDecimal qMargin = Finance.floorStep(budget.multiply(leverage, MC).divide(entry, MC), step);
        BigDecimal qCash = Finance.allInQuantity(available, entry, leverage, feeRate, step);
        BigDecimal remainingNotional = limits.maxTotalNotional().subtract(existingNotional).max(BigDecimal.ZERO);
        BigDecimal qNotional = limits.maxPositionNotional().min(remainingNotional).divide(entry, MC);
        BigDecimal result = qMargin.min(qCash).min(qNotional);
        if (stop != null) {
            BigDecimal distance = entry.subtract(stop).abs().add(unitCostBuffer);
            Decimal.positive(distance);
            BigDecimal riskBudget = equity.max(BigDecimal.ZERO).multiply(limits.riskFraction(), MC);
            result = result.min(riskBudget.divide(distance, MC));
        }
        return Finance.floorStep(result, step); // zero sonuç WAIT/reject; minimum lota yükseltme yok.
    }

    public static boolean dailyHalt(BigDecimal startEquity, BigDecimal equity, BigDecimal netFlows, BigDecimal limitFraction) {
        if (startEquity.signum() <= 0) {
            return true;
        }
        BigDecimal loss = startEquity.add(netFlows).subtract(equity);
        return loss.compareTo(startEquity.multiply(limitFraction, MC)) >= 0;
    }
}
