package com.crypto.strategyservice;

import com.crypto.strategyservice.application.port.ModelClient;
import com.crypto.strategyservice.application.service.AnalysisFlow;
import com.crypto.strategyservice.domain.model.PredictionResult;
import com.crypto.strategyservice.domain.service.AdaptiveNumericPolicy;
import com.crypto.strategyservice.domain.service.NumericPolicy;
import com.crypto.strategyservice.infrastructure.client.fixture.FakeModelClient;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class StrategyFlowTest {

    private static final UUID REQUEST_ID = UUID.randomUUID();
    private static final UUID INSTRUMENT_ID = UUID.randomUUID();
    private static final UUID DATASET_ID = UUID.randomUUID();
    private static final UUID SNAPSHOT_ID = UUID.randomUUID();
    private static final UUID BUNDLE_ID = UUID.randomUUID();

    private static final Instant AS_OF =
            Instant.parse("2026-10-22T10:00:00Z");

    private static final String SCHEMA_HASH =
            "sha256:" + "b".repeat(64);

    private static final ModelClient.Bundle PINNED_BUNDLE =
            new ModelClient.Bundle(
                    BUNDLE_ID,
                    "features-v1",
                    SCHEMA_HASH,
                    List.of(60, 900, 3600, 86400, 604800),
                    "DEVELOPMENT_CANDIDATE"
            );

    // Yalnız test girdisi; üretim için optimize edilmiş parametreler değildir.
    private static final NumericPolicy.Artifact POLICY =
            new NumericPolicy.Artifact(
                    "test-policy-v1",
                    0.01,
                    0.95,
                    new BigDecimal("2"),
                    new BigDecimal("3"),
                    new BigDecimal("2"),
                    0.50
            );

    @Test
    void shouldReturnSuccessfulWaitForFixtureModel() {
        var model = new FakeModelClient();
        var bundle = model.activeBundle(900);

        var result = evaluate(model, bundle);

        assertEquals("SUCCEEDED", result.status());
        assertEquals("WAIT", result.action());
        assertEquals("INSUFFICIENT_DATA", result.reason());
        assertTrue(
                result.prediction().reasonCodes().contains("FIXTURE_MODEL")
        );
    }

    @Test
    void shouldKeepReadyPredictionPendingUntilPolicyEvaluation() {
        var result = evaluate(
                client(request -> readyPrediction(
                        request,
                        Math.log1p(0.05),
                        SCHEMA_HASH
                )),
                PINNED_BUNDLE
        );

        assertEquals("RUNNING", result.status());
        assertNull(result.action());
        assertEquals("POLICY_PENDING", result.reason());
        assertNotNull(result.prediction());
    }

    @Test
    void shouldProduceLongWithExpectedPriceLevels() {
        var prediction = readyPrediction(
                request(),
                Math.log1p(0.05),
                SCHEMA_HASH
        );

        var outcome = applyPolicy(prediction, 1.0);

        assertEquals("LONG", outcome.action());
        assertDecimalEquals("90", outcome.stopLoss());
        assertDecimalEquals("130", outcome.takeProfit());
        assertDecimalEquals("2", outcome.suggestedLeverage());

        // Aynı girdiler aynı sonucu vermeli.
        assertEquals(outcome, applyPolicy(prediction, 1.0));
    }

    @Test
    void shouldProduceShortWithExpectedPriceLevels() {
        var prediction = readyPrediction(
                request(),
                Math.log1p(-0.05),
                SCHEMA_HASH
        );

        var outcome = applyPolicy(prediction, 1.0);

        assertEquals("SHORT", outcome.action());
        assertDecimalEquals("110", outcome.stopLoss());
        assertDecimalEquals("70", outcome.takeProfit());
    }

    @Test
    void shouldWaitWhenNetEdgeIsInsufficient() {
        var prediction = readyPrediction(
                request(),
                Math.log1p(0.005),
                SCHEMA_HASH
        );

        var outcome = applyPolicy(prediction, 1.0);

        assertEquals("WAIT", outcome.action());
        assertEquals("INSUFFICIENT_EDGE", outcome.reason());
        assertNull(outcome.stopLoss());
        assertNull(outcome.takeProfit());
        assertNull(outcome.suggestedLeverage());
    }

    @Test
    void shouldWaitWhenCoverageIsInsufficient() {
        var prediction = readyPrediction(
                request(),
                Math.log1p(0.05),
                SCHEMA_HASH
        );

        var outcome = applyPolicy(prediction, 0.80);

        assertEquals("WAIT", outcome.action());
        assertEquals("POLICY_GATE", outcome.reason());
    }

    @Test
    void shouldFailWhenModelCallFails() {
        var model = client(request -> {
            throw new IllegalStateException("MODEL_TIMEOUT");
        });

        var result = evaluate(model, PINNED_BUNDLE);

        assertEquals("FAILED", result.status());
        assertEquals("MODEL_UNAVAILABLE", result.reason());
        assertNull(result.action());
        assertNull(result.prediction());
    }

    @Test
    void shouldRejectFeatureSchemaMismatch() {
        var model = client(request -> readyPrediction(
                request,
                Math.log1p(0.05),
                "sha256:" + "c".repeat(64)
        ));

        var result = evaluate(model, PINNED_BUNDLE);

        assertEquals("FAILED", result.status());
        assertEquals("FEATURE_SCHEMA_MISMATCH", result.reason());
        assertNull(result.action());
    }

    @Test
    void shouldRejectWrongRequestId() {
        var model = client(request -> {
            var wrongRequest = new ModelClient.Request(
                    UUID.randomUUID(),
                    request.instrumentId(),
                    request.horizonSeconds(),
                    request.asOf(),
                    request.modelBundleId(),
                    request.marketDatasetId(),
                    request.marketSnapshotId(),
                    request.featureVersion()
            );

            return readyPrediction(
                    wrongRequest,
                    Math.log1p(0.05),
                    SCHEMA_HASH
            );
        });

        var result = evaluate(model, PINNED_BUNDLE);

        assertEquals("FAILED", result.status());
        assertEquals("FEATURE_SCHEMA_MISMATCH", result.reason());
        assertNull(result.action());
    }

    @Test
    void shouldRejectWrongModelBundleId() {
        var model = client(request -> {
            var wrongRequest = new ModelClient.Request(
                    request.requestId(),
                    request.instrumentId(),
                    request.horizonSeconds(),
                    request.asOf(),
                    UUID.randomUUID(),
                    request.marketDatasetId(),
                    request.marketSnapshotId(),
                    request.featureVersion()
            );

            return readyPrediction(
                    wrongRequest,
                    Math.log1p(0.05),
                    SCHEMA_HASH
            );
        });

        var result = evaluate(model, PINNED_BUNDLE);

        assertEquals("FAILED", result.status());
        assertEquals("FEATURE_SCHEMA_MISMATCH", result.reason());
        assertNull(result.action());
    }

    @Test
    void shouldUsePinnedBundleWithoutLookingUpActiveBundleAgain() {
        ModelClient model = new ModelClient() {
            @Override
            public Bundle activeBundle(int horizonSeconds) {
                fail("Pinned analysis must not fetch the active bundle again");
                return null;
            }

            @Override
            public PredictionResult predict(Request request) {
                assertEquals(BUNDLE_ID, request.modelBundleId());
                assertEquals("features-v1", request.featureVersion());

                return readyPrediction(
                        request,
                        Math.log1p(0.05),
                        SCHEMA_HASH
                );
            }
        };

        var result = evaluate(model, PINNED_BUNDLE);

        assertEquals("RUNNING", result.status());
        assertEquals(BUNDLE_ID, result.prediction().modelBundleId());
    }

    private static AnalysisFlow.Result evaluate(
            ModelClient model,
            ModelClient.Bundle bundle
    ) {
        return new AnalysisFlow(model).evaluate(
                REQUEST_ID,
                INSTRUMENT_ID,
                900,
                AS_OF,
                DATASET_ID,
                SNAPSHOT_ID,
                bundle
        );
    }

    private static ModelClient.Request request() {
        return new ModelClient.Request(
                REQUEST_ID,
                INSTRUMENT_ID,
                900,
                AS_OF,
                BUNDLE_ID,
                DATASET_ID,
                SNAPSHOT_ID,
                "features-v1"
        );
    }

    private static PredictionResult readyPrediction(
            ModelClient.Request request,
            double predictedLogReturn,
            String schemaHash
    ) {
        return new PredictionResult(
                request.requestId(),
                PredictionResult.Quality.READY,
                List.of("TEST_FIXTURE"),
                request.modelBundleId(),
                request.featureVersion(),
                schemaHash,
                SNAPSHOT_ID,
                request.asOf(),
                request.horizonSeconds(),
                predictedLogReturn,
                0.10,
                "test-calibration-v1",
                Map.of(),
                1
        );
    }

    private static NumericPolicy.Outcome applyPolicy(
            PredictionResult prediction,
            double coverage
    ) {
        return NumericPolicy.evaluate(
                prediction,
                POLICY,
                new BigDecimal("100"),
                new BigDecimal("5"),
                coverage,
                0.002
        );
    }

    private static ModelClient client(
            Function<ModelClient.Request, PredictionResult> response
    ) {
        return new ModelClient() {
            @Override
            public Bundle activeBundle(int horizonSeconds) {
                return PINNED_BUNDLE;
            }

            @Override
            public PredictionResult predict(Request request) {
                return response.apply(request);
            }
        };
    }

    private static void assertDecimalEquals(
            String expected,
            BigDecimal actual
    ) {
        assertNotNull(actual);
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }

    @Test
    void adaptivePolicyProducesDynamicAssetHorizonAndRiskSpecificLeverageWithinSafetyCaps() {
        PredictionResult btc1h = new PredictionResult(
                REQUEST_ID,
                PredictionResult.Quality.READY,
                List.of("QUANT_MODEL_V3", "HTF_TREND_CONFIRMED", "VOLUME_CONFIRMED"),
                BUNDLE_ID,
                "features-quant-v3",
                SCHEMA_HASH,
                SNAPSHOT_ID,
                AS_OF,
                3600,
                0.012,
                0.0085,
                "walkforward-ridge-quant-v3-h3600",
                Map.of(
                        "signalConfidence", 0.78,
                        "efficiencyRatio", 0.62,
                        "volRegimeRatio", 1.05,
                        "regime", "TREND_UP"
                ),
                3
        );

        var low = AdaptiveNumericPolicy.evaluateAdaptive(
                btc1h, null, new BigDecimal("90000"), new BigDecimal("520"), 1.0, 0.0012, "LOW", 50, 125
        );
        var med = AdaptiveNumericPolicy.evaluateAdaptive(
                btc1h, null, new BigDecimal("90000"), new BigDecimal("520"), 1.0, 0.0012, "MEDIUM", 50, 125
        );
        var high = AdaptiveNumericPolicy.evaluateAdaptive(
                btc1h, null, new BigDecimal("90000"), new BigDecimal("520"), 1.0, 0.0012, "HIGH", 50, 125
        );

        assertEquals("LONG", low.action());
        assertEquals("LONG", med.action());
        assertEquals("LONG", high.action());
        assertTrue(low.suggestedLeverage().compareTo(BigDecimal.ONE) >= 0);
        assertTrue(med.suggestedLeverage().compareTo(low.suggestedLeverage()) > 0);
        assertTrue(high.suggestedLeverage().compareTo(med.suggestedLeverage()) > 0);
        assertTrue(med.suggestedLeverage().compareTo(new BigDecimal("2")) > 0);
        assertTrue(high.suggestedLeverage().compareTo(new BigDecimal("50")) <= 0);
        assertTrue(high.stopLoss().compareTo(new BigDecimal("90000")) < 0);
        assertTrue(high.takeProfit().compareTo(new BigDecimal("90000")) > 0);

        // Session cap (5x Muhafazakar Koruma) MUST never be violated even when HIGH selected
        var cappedSession = AdaptiveNumericPolicy.evaluateAdaptive(
                btc1h, null, new BigDecimal("90000"), new BigDecimal("520"), 1.0, 0.0012, "HIGH", 5, 125
        );
        assertEquals(0, new BigDecimal("5").compareTo(cappedSession.suggestedLeverage()));

        // High-ATR volatile altcoin on weekly horizon MUST lower leverage well below session cap so SL precedes liquidation
        PredictionResult doge1w = new PredictionResult(
                REQUEST_ID,
                PredictionResult.Quality.READY,
                List.of("QUANT_MODEL_V3", "HTF_TREND_CONFIRMED"),
                BUNDLE_ID,
                "features-quant-v3",
                SCHEMA_HASH,
                SNAPSHOT_ID,
                AS_OF,
                604800,
                0.06,
                0.055,
                "walkforward-ridge-quant-v3-h604800",
                Map.of(
                        "signalConfidence", 0.72,
                        "efficiencyRatio", 0.45,
                        "volRegimeRatio", 1.25,
                        "regime", "TREND_UP"
                ),
                4
        );
        var dogeHigh = AdaptiveNumericPolicy.evaluateAdaptive(
                doge1w, null, new BigDecimal("0.38000"), new BigDecimal("0.01500"), 1.0, 0.0012, "HIGH", 50, 50
        );
        assertEquals("LONG", dogeHigh.action());
        assertTrue(dogeHigh.suggestedLeverage().compareTo(high.suggestedLeverage()) < 0);
        // SL distance is ~5.5%+; isolated liquidation distance (1/lev) must remain comfortably wider than SL distance
        double slPct = new BigDecimal("0.38000").subtract(dogeHigh.stopLoss()).doubleValue() / 0.38;
        assertTrue(1.0 / dogeHigh.suggestedLeverage().doubleValue() > slPct);

        // Choppy regime enters WAIT on LOW / MEDIUM cleanly
        PredictionResult choppy = new PredictionResult(
                REQUEST_ID,
                PredictionResult.Quality.READY,
                List.of("QUANT_MODEL_V3", "CHOPPY_REGIME"),
                BUNDLE_ID,
                "features-quant-v3",
                SCHEMA_HASH,
                SNAPSHOT_ID,
                AS_OF,
                900,
                0.005,
                0.004,
                "walkforward-ridge-quant-v3-h900",
                Map.of("signalConfidence", 0.54, "regime", "CHOPPY"),
                2
        );
        var choppyWait = AdaptiveNumericPolicy.evaluateAdaptive(
                choppy, null, new BigDecimal("90000"), new BigDecimal("350"), 1.0, 0.0012, "MEDIUM", 20, 125
        );
        assertEquals("WAIT", choppyWait.action());
        assertNull(choppyWait.suggestedLeverage());

        // HIGH ("Yüksek - Maks. Kâr") yields an actionable directional setup even on mild chop or tight 1m edge
        var choppyHigh = AdaptiveNumericPolicy.evaluateAdaptive(
                choppy, null, new BigDecimal("90000"), new BigDecimal("350"), 1.0, 0.0012, "HIGH", 50, 125
        );
        assertEquals("LONG", choppyHigh.action());
        assertNotNull(choppyHigh.suggestedLeverage());
        assertTrue(choppyHigh.suggestedLeverage().compareTo(BigDecimal.ONE) >= 1);

        PredictionResult btc1m = new PredictionResult(
                REQUEST_ID,
                PredictionResult.Quality.READY,
                List.of("QUANT_MODEL_V3", "CHOPPY_REGIME", "LOW_CONFIDENCE"),
                BUNDLE_ID,
                "features-quant-v3",
                SCHEMA_HASH,
                SNAPSHOT_ID,
                AS_OF,
                60,
                -0.0010,
                0.0009,
                "walkforward-ridge-quant-v3-h60",
                Map.of("signalConfidence", 0.42, "efficiencyRatio", 0.16, "atrPct", 0.0011, "rsi14", 44.2, "regime", "CHOPPY"),
                2
        );
        var btc1mHigh = AdaptiveNumericPolicy.evaluateAdaptive(
                btc1m, null, new BigDecimal("90000"), new BigDecimal("95"), 1.0, 0.0010, "HIGH", 50, 125
        );
        assertEquals("SHORT", btc1mHigh.action());
        assertNotNull(btc1mHigh.suggestedLeverage());

        String waitSummary = AdaptiveNumericPolicy.buildWaitSummary(
                btc1m, "CHOPPY_MARKET_WAIT", 60, new BigDecimal("90000"), new BigDecimal("95"), "MEDIUM"
        );
        assertTrue(waitSummary.contains("Düşüş (SHORT) Eğilimi"));
        assertTrue(waitSummary.contains("Yüksek (Maks. Kâr)"));
        assertFalse(waitSummary.contains("CHOPPY_MARKET_WAIT"));
        assertFalse(waitSummary.contains("QUANT_MODEL_V3"));

        // features-quant-v4 GBDT + Triple-Barrier predictions cleanly evaluate through AdaptiveNumericPolicy
        PredictionResult ethV4 = new PredictionResult(
                REQUEST_ID,
                PredictionResult.Quality.READY,
                List.of("QUANT_MODEL_V4", "ORDER_FLOW_CONFIRMED", "LIQUIDITY_SWEEP_ALIGNED", "HTF_TREND_CONFIRMED"),
                BUNDLE_ID,
                "features-quant-v4",
                SCHEMA_HASH,
                SNAPSHOT_ID,
                AS_OF,
                3600,
                0.015,
                0.009,
                "walkforward-gbdt-triple-barrier-v4-h3600",
                Map.of(
                        "signalConfidence", 0.74,
                        "winProbabilityEstimate", 0.54,
                        "efficiencyRatio", 0.58,
                        "volRegimeRatio", 1.12,
                        "regime", "TREND_UP"
                ),
                3
        );
        var v4Med = AdaptiveNumericPolicy.evaluateAdaptive(
                ethV4, null, new BigDecimal("3400"), new BigDecimal("26"), 1.0, 0.0012, "MEDIUM", 20, 100
        );
        assertEquals("LONG", v4Med.action());
        assertEquals("QUANT_ADAPTIVE_POLICY", v4Med.reason());
        assertTrue(v4Med.suggestedLeverage().compareTo(BigDecimal.ONE) > 0);
        assertTrue(v4Med.suggestedLeverage().compareTo(new BigDecimal("20")) <= 0);
    }
}