package com.crypto.strategyservice.infrastructure.client.fixture;

import com.crypto.strategyservice.application.port.ModelClient;
import com.crypto.strategyservice.domain.model.PredictionResult;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@Profile("fixture")
public class FakeModelClient implements ModelClient {

    private static final UUID BUNDLE_ID = UUID.fromString(
            "44444444-4444-4444-8444-444444444444"
    );

    private static final UUID FEATURE_SNAPSHOT_ID = UUID.fromString(
            "66666666-6666-4666-8666-666666666666"
    );

    private static final String FEATURE_VERSION = "features-v1";
    private static final String FEATURE_SCHEMA_HASH =
            "sha256:" + "b".repeat(64);

    @Override
    public Bundle activeBundle(int horizonSeconds) {
        return new Bundle(
                BUNDLE_ID,
                FEATURE_VERSION,
                FEATURE_SCHEMA_HASH,
                List.of(60, 900, 3600, 86400, 604800),
                "DEVELOPMENT_CANDIDATE"
        );
    }

    @Override
    public PredictionResult predict(Request request) {
        return new PredictionResult(
                request.requestId(),
                PredictionResult.Quality.INSUFFICIENT_DATA,
                List.of("FIXTURE_MODEL", "INSUFFICIENT_HISTORY"),
                request.modelBundleId(),
                request.featureVersion(),
                FEATURE_SCHEMA_HASH,
                FEATURE_SNAPSHOT_ID,
                request.asOf(),
                request.horizonSeconds(),
                null,
                null,
                null,
                Map.of(),
                1
        );
    }
}