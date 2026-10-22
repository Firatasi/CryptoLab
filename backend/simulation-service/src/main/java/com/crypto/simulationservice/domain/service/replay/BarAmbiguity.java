package com.crypto.simulationservice.domain.service.replay;

import com.crypto.financecore.finance.PositionSide;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Sorumluluk: Girdilerden iş kararı/hesap üretir.
 * Okuma ipucu: Aynı girdinin aynı sonucu üretmesi beklenir; DB/ağ yan etkisi ekleme.
 */
public final class BarAmbiguity {

    public record Trigger(String reason, boolean ambiguous) {
    }

    private BarAmbiguity() {
    }

    public static Trigger bracket(
            PositionSide side,
            BigDecimal markLow,
            BigDecimal markHigh,
            BigDecimal stop,
            BigDecimal target
    ) {
        boolean longSide = side == PositionSide.LONG;
        boolean hitStop = stop != null && (longSide ? markLow.compareTo(stop) <= 0 : markHigh.compareTo(stop) >= 0);
        boolean hitTarget = target != null && (longSide ? markHigh.compareTo(target) >= 0 : markLow.compareTo(target) <= 0);
        // Adverse-first: aynı mumda ikisi varsa stop. Bu bir path tahmini, kesin tick sırası değil.
        if (hitStop) {
            return new Trigger("STOP_LOSS", hitTarget);
        }
        if (hitTarget) {
            return new Trigger("TAKE_PROFIT", false);
        }
        return new Trigger("NONE", false);
    }

    /**
     * Lookahead koruması: Bir mumun kapanışından (availableAt) üretilen sinyal veya feature,
     * kendi mumunun içindeki fiyattan veya daha erken bir zamandan fill olamaz.
     * En erken fill yalnız sinyal zamanından sonraki (fillTime.isAfter(signalAvailableAt)) uygun quote/bar open'da gerçekleşir.
     */
    public static void verifyNoLookahead(Instant featureAvailableAt, Instant decisionTime, Instant fillTime) {
        if (featureAvailableAt == null || decisionTime == null || fillTime == null) {
            throw new IllegalArgumentException("INVALID_LOOKAHEAD_TIMESTAMPS");
        }
        if (featureAvailableAt.isAfter(decisionTime)) {
            throw new IllegalArgumentException("LOOKAHEAD_FEATURE_NOT_YET_AVAILABLE");
        }
        if (!fillTime.isAfter(decisionTime)) {
            throw new IllegalArgumentException("SAME_BAR_LOOKAHEAD_FILL_FORBIDDEN");
        }
    }
}

