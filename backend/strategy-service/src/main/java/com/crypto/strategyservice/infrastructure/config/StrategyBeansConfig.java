package com.crypto.strategyservice.infrastructure.config;

import com.crypto.strategyservice.application.port.ModelClient;
import com.crypto.strategyservice.application.port.TrainingProvider;
import com.crypto.strategyservice.infrastructure.client.HttpTrainingProvider;
import com.crypto.strategyservice.infrastructure.client.fixture.FakeModelClient;
import com.crypto.strategyservice.infrastructure.client.fixture.FakeTrainingProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.net.URI;
import java.time.Clock;

@Configuration
public class StrategyBeansConfig {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean(ModelClient.class)
    public ModelClient fallbackFixtureModelClient() {
        return new ModelClient() {
            private static final java.util.UUID BUNDLE_ID = java.util.UUID.fromString(
                    "44444444-4444-4444-8444-444444444444"
            );
            private static final java.util.UUID FEATURE_SNAPSHOT_ID = java.util.UUID.fromString(
                    "66666666-6666-4666-8666-666666666666"
            );
            private static final String FEATURE_VERSION = "features-v1";
            private static final String FEATURE_SCHEMA_HASH = "sha256:" + "b".repeat(64);

            @Override
            public Bundle activeBundle(int horizonSeconds) {
                return new Bundle(
                        BUNDLE_ID,
                        FEATURE_VERSION,
                        FEATURE_SCHEMA_HASH,
                        java.util.List.of(60, 900, 3600, 86400, 604800),
                        "ACTIVE"
                );
            }

            @Override
            public com.crypto.strategyservice.domain.model.PredictionResult predict(Request request) {
                boolean shortBias = Math.abs(request.instrumentId().hashCode() + request.horizonSeconds()) % 3 == 1;
                double logReturn = shortBias ? -0.024 : 0.026;
                return new com.crypto.strategyservice.domain.model.PredictionResult(
                        request.requestId(),
                        com.crypto.strategyservice.domain.model.PredictionResult.Quality.READY,
                        java.util.List.of("POSITIVE_NET_EDGE", "MOMENTUM_ALIGNMENT", "VOLATILITY_WITHIN_BOUNDS"),
                        request.modelBundleId(),
                        request.featureVersion(),
                        FEATURE_SCHEMA_HASH,
                        FEATURE_SNAPSHOT_ID,
                        request.asOf(),
                        request.horizonSeconds(),
                        logReturn,
                        0.018,
                        "calibration-v1",
                        java.util.Map.of("rsi14", shortBias ? 68.4 : 42.1, "adx14", 27.5, "atrPct", 0.012),
                        1
                );
            }
        };
    }

    @Bean
    @Profile("training-http & !fixture")
    public TrainingProvider httpTrainingProvider(
            @Value("${training.base-url}") String baseUrl,
            @Value("${training.service-secret}") String serviceSecret,
            Clock clock
    ) {
        return new HttpTrainingProvider(URI.create(baseUrl), serviceSecret, clock);
    }

    @Bean
    @ConditionalOnMissingBean(TrainingProvider.class)
    public FakeTrainingProvider fallbackFixtureTrainingProvider() {
        return new FakeTrainingProvider();
    }
}
