package com.crypto.simulationservice.domain.service;

import java.math.BigDecimal;

import com.crypto.financecore.finance.Finance;
import com.crypto.simulationservice.domain.model.ExecutionPlan;

/**
 * Sorumluluk: Girdilerden iş kararı/hesap üretir. Okuma ipucu: Aynı girdinin
 * aynı sonucu üretmesi beklenir; DB/ağ yan etkisi ekleme.
 */
public final class LimitDecision {

    public record Result(String action, String reason, BigDecimal price) {}

    private LimitDecision() {
    }

    public static Result evaluate(ExecutionPlan p, BigDecimal bid, BigDecimal ask, BigDecimal bidQty,
            BigDecimal askQty, BigDecimal tick, BigDecimal participation) {
        boolean buy = "LONG".equals(p.side());
        BigDecimal visible = buy ? askQty : bidQty;
        if ("LIMIT".equals(p.orderType())) {
            if (buy ? ask.compareTo(p.limitPrice()) > 0 : bid.compareTo(p.limitPrice()) < 0) {
                return new Result("WAIT", "NOT_MARKETABLE", null);
            }
        }
        BigDecimal price = Finance.marketPrice(buy, bid, ask, tick, p.maxSlippageBps());
        if ("LIMIT".equals(p.orderType()) && (buy ? price.compareTo(p.limitPrice()) > 0 : price.compareTo(p.limitPrice()) < 0)) {
            return new Result("WAIT", "ADVERSE_PRICE_OUTSIDE_LIMIT", null);
        }
        if (p.quantity().compareTo(visible.multiply(participation)) > 0) {
            return new Result("REJECT", "LIQUIDITY_LIMIT", null);
        }
        return new Result("FILL", null, price);
    }
}
