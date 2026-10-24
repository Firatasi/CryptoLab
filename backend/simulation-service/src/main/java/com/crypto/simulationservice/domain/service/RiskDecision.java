package com.crypto.simulationservice.domain.service;

import com.crypto.financecore.finance.Finance;
import com.crypto.financecore.finance.PositionSide;
import com.crypto.financecore.finance.MaintenanceTier;
import java.math.BigDecimal;
import java.util.List;

/**
 * Sorumluluk: Girdilerden iş kararı/hesap üretir. Okuma ipucu: Aynı girdinin
 * aynı sonucu üretmesi beklenir; DB/ağ yan etkisi ekleme.
 */
public final class RiskDecision {

    public enum Action {
        NONE, LIQUIDATE, STOP_LOSS, TAKE_PROFIT
    }

    private RiskDecision() {
    }

    public static Action onMark(PositionSide side, BigDecimal q, BigDecimal entry, BigDecimal mark,
            BigDecimal isolated, BigDecimal stop, BigDecimal target, List<MaintenanceTier> tiers) {
        // Aynı engine adımında liquidation stop'tan önce gelir: proje tie politikası.
        if (Finance.liquidates(side, q, entry, mark, isolated, tiers)) {
            return Action.LIQUIDATE;
        }
        boolean longSide = side == PositionSide.LONG;
        if (stop != null && (longSide ? mark.compareTo(stop) <= 0 : mark.compareTo(stop) >= 0)) {
            return Action.STOP_LOSS;
        }
        if (target != null && (longSide ? mark.compareTo(target) >= 0 : mark.compareTo(target) <= 0)) {
            return Action.TAKE_PROFIT;
        }
        return Action.NONE;
    }
}
