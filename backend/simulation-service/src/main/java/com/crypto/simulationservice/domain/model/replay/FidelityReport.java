package com.crypto.simulationservice.domain.model.replay;

import java.util.Set;

/**
 * Sorumluluk: İş verisini ve kendi değişmez kurallarını temsil eder.
 * Okuma ipucu: Modelin HTTP veya veritabanı biçimine bağımlı olup olmadığını kontrol et.
 */
public record FidelityReport(
        String dataLayer,
        String fidelity,
        long ambiguousBarCount,
        boolean missingMark,
        boolean gaps,
        boolean lateFunding
) {

    public static FidelityReport classify(
            String layer,
            boolean gap,
            boolean missingMark,
            long ambiguous,
            boolean lateFunding
    ) {
        if (!Set.of("RECORDED_TICKS", "PAIRED_OHLC", "TRADE_ONLY").contains(layer) || ambiguous < 0) {
            throw new IllegalArgumentException("INVALID_FIDELITY_INPUT");
        }
        String f = "TRADE_ONLY".equals(layer) || missingMark
                ? "INDETERMINATE"
                : gap || lateFunding
                ? "DEGRADED"
                : "PAIRED_OHLC".equals(layer) || ambiguous > 0
                ? "APPROXIMATE"
                : "EXACT_WITHIN_MODEL";
        return new FidelityReport(layer, f, ambiguous, missingMark, gap, lateFunding);
    }
}

