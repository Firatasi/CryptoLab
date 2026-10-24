package com.crypto.strategyservice.application.service;

import com.crypto.strategyservice.application.port.ModelClient;
import com.crypto.strategyservice.domain.model.PredictionResult;

import java.time.Instant;
import java.util.UUID;

public final class AnalysisFlow {

    public record Result(
            String status,
            String action,
            String reason,
            PredictionResult prediction
    ) {
    }

    private final ModelClient model;

    public AnalysisFlow(ModelClient model) {
        this.model = model;
    }

    public Result evaluate(
            UUID requestId,
            UUID instrumentId,
            int horizonSeconds,
            Instant asOf,
            UUID datasetId,
            UUID snapshotId,
            ModelClient.Bundle pinnedBundle
    ) {
        if (!pinnedBundle.supportedHorizons().contains(horizonSeconds)) {
            return new Result(
                    "FAILED",
                    null,
                    "MODEL_NOT_READY",
                    null
            );
        }

        try {
            var request = new ModelClient.Request(
                    requestId,
                    instrumentId,
                    horizonSeconds,
                    asOf,
                    pinnedBundle.bundleId(),
                    datasetId,
                    snapshotId,
                    pinnedBundle.featureVersion()
            );

            PredictionResult prediction = model.predict(request);

            if (!prediction.requestId().equals(requestId)
                    || !prediction.modelBundleId().equals(pinnedBundle.bundleId())
                    || !prediction.featureVersion().equals(pinnedBundle.featureVersion())
                    || !prediction.featureSchemaHash().equals(pinnedBundle.featureSchemaHash())
                    || prediction.horizonSeconds() != horizonSeconds
                    || !prediction.asOf().equals(asOf)) {
                return new Result(
                        "FAILED",
                        null,
                        "FEATURE_SCHEMA_MISMATCH",
                        null
                );
            }

            if (prediction.quality() != PredictionResult.Quality.READY) {
                return new Result(
                        "SUCCEEDED",
                        "WAIT",
                        prediction.quality().name(),
                        prediction
                );
            }

            // Yön kararı NumericPolicy tarafından verilecek.
            return new Result(
                    "RUNNING",
                    null,
                    "POLICY_PENDING",
                    prediction
            );
        } catch (RuntimeException exception) {
            return new Result(
                    "FAILED",
                    null,
                    "MODEL_UNAVAILABLE",
                    null
            );
        }
    }
}