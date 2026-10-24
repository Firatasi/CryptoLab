package com.crypto.strategyservice.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record PredictionResult(
        UUID requestId,
        Quality quality,
        List<String> reasonCodes,
        UUID modelBundleId,
        String featureVersion,
        String featureSchemaHash,
        UUID featureSnapshotId,
        Instant asOf,
        int horizonSeconds,
        Double predictedLogReturn,
        Double volatilityEstimate,
        String calibrationVersion,
        Map<String, Object> observedFeatureSummary,
        long latencyMs
) {

    public enum Quality {
        READY,
        INSUFFICIENT_DATA,
        OUT_OF_DOMAIN,
        STALE_DATA
    }

    public PredictionResult {
        reasonCodes = List.copyOf(reasonCodes);
        observedFeatureSummary = Map.copyOf(observedFeatureSummary);

        if (!Set.of(60, 900, 3600, 86400, 604800)
                .contains(horizonSeconds) || latencyMs < 0) {
            throw new IllegalArgumentException("INVALID_PREDICTION");
        }

        if (quality == Quality.READY) {
            if (predictedLogReturn == null
                    || volatilityEstimate == null
                    || !Double.isFinite(predictedLogReturn)
                    || !Double.isFinite(volatilityEstimate)
                    || volatilityEstimate < 0) {
                throw new IllegalArgumentException("INVALID_NUMERIC_OUTPUT");
            }
        } else if (predictedLogReturn != null || volatilityEstimate != null) {
            throw new IllegalArgumentException("QUALITY_OUTPUT_MISMATCH");
        }
    }
}