package com.crypto.strategyservice.application.port;

import com.crypto.strategyservice.domain.model.PredictionResult;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ModelClient {

    record Bundle(
            UUID bundleId,
            String featureVersion,
            String featureSchemaHash,
            List<Integer> supportedHorizons,
            String releaseStatus
    ) {
    }

    record Request(
            UUID requestId,
            UUID instrumentId,
            int horizonSeconds,
            Instant asOf,
            UUID modelBundleId,
            UUID marketDatasetId,
            UUID marketSnapshotId,
            String featureVersion
    ) {
    }

    Bundle activeBundle(int horizonSeconds);

    PredictionResult predict(Request request);
}