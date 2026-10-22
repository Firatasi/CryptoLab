package com.crypto.strategyservice.application.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Sorumluluk: Piyasa veri kapsamını (coverage), doğrulanmış referans fiyatı/ATR değerlerini
 * ve eksik veri için backfill tetiklemesini soyutlar.
 */
public interface MarketCoveragePort {

    record MarketEvaluation(
            UUID datasetId,
            UUID snapshotId,
            Instant asOf,
            double coverage,
            BigDecimal referencePrice,
            BigDecimal verifiedAtr,
            boolean sufficientCoverage
    ) {
        public MarketEvaluation {
            if (datasetId == null || snapshotId == null || asOf == null) {
                throw new IllegalArgumentException("INVALID_MARKET_EVALUATION");
            }
            if (!Double.isFinite(coverage) || coverage < 0.0 || coverage > 1.0) {
                throw new IllegalArgumentException("INVALID_MARKET_COVERAGE");
            }
            if (sufficientCoverage && (referencePrice == null || referencePrice.signum() <= 0
                    || verifiedAtr == null || verifiedAtr.signum() <= 0)) {
                throw new IllegalArgumentException("INVALID_VERIFIED_MARKET_PRICES");
            }
        }
    }

    MarketEvaluation evaluate(UUID instrumentId, int horizonSeconds, Instant asOf);

    void triggerBackfill(UUID backfillId, UUID instrumentId, int horizonSeconds, Instant asOf);
}

