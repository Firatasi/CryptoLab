package com.crypto.simulationservice.domain.service;

import com.crypto.financecore.finance.Finance;
import com.crypto.financecore.finance.PositionSide;
import java.math.BigDecimal;

/**
 * Sorumluluk: Girdilerden iş kararı/hesap üretir. Okuma ipucu: Aynı girdinin
 * aynı sonucu üretmesi beklenir; DB/ağ yan etkisi ekleme.
 */
public final class ExitDecision {

    public record Result(String status, String reason, BigDecimal quantity, BigDecimal price,
            Finance.Closing closing, boolean liquidityApproximation) {}

    private ExitDecision() {
    }

    public static Result decide(PositionSide side, BigDecimal q, BigDecimal requested, BigDecimal entry,
            BigDecimal isolated, BigDecimal bid, BigDecimal ask, BigDecimal bidQty, BigDecimal askQty,
            BigDecimal tick, BigDecimal slippageBps, BigDecimal participation, BigDecimal takerRate,
            BigDecimal liquidationRate, boolean liquidating, boolean quoteFresh) {
        if (!quoteFresh) {
            return new Result("EXIT_PENDING_DATA", "STALE_QUOTE", requested, null, null, false);
        }
        boolean buy = side == PositionSide.SHORT;
        BigDecimal amount = liquidating ? q : requested;
        if (amount.signum() <= 0 || amount.compareTo(q) > 0) {
            throw new IllegalArgumentException("INVALID_CLOSE_QUANTITY");
        }
        BigDecimal visible = buy ? askQty : bidQty;
        if (!liquidating && amount.compareTo(visible.multiply(participation)) > 0) {
            return new Result("NEW", "LIQUIDITY_LIMIT", amount, null, null, false);
        }
        BigDecimal price = Finance.marketPrice(buy, bid, ask, tick, slippageBps);
        var close = Finance.close(side, q, amount, entry, price, isolated, liquidating ? liquidationRate : takerRate);
        boolean forced = false;
        if (close.forceFullClose()) {
            // Negatif partial residual uygulanmaz; tüm kalan q için risk close hesaplanır.
            amount = q;
            forced = true;
            close = Finance.close(side, q, q, entry, price, isolated, takerRate);
        }
        return new Result("FILLED", liquidating ? "LIQUIDATION" : close.insurance().signum() > 0 ? "INSOLVENT_EXIT" : forced ? "FORCED_RISK_CLOSE" : "USER_CLOSE",
                amount, price, close, liquidating || forced);
    }
}
