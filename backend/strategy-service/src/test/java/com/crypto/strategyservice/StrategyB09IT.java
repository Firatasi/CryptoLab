package com.crypto.strategyservice;

import com.crypto.strategyservice.application.port.ExplanationClient;
import com.crypto.strategyservice.application.port.MarketCoveragePort;
import com.crypto.strategyservice.application.port.MarketUniversePort;
import com.crypto.strategyservice.application.port.ModelClient;
import com.crypto.strategyservice.application.port.SessionContextReader;
import com.crypto.strategyservice.application.repository.AnalysisJobRepository;
import com.crypto.strategyservice.application.repository.ScanJobRepository;
import com.crypto.strategyservice.application.service.AnalysisJobTransactions;
import com.crypto.strategyservice.application.service.AnalysisWorker;
import com.crypto.strategyservice.application.service.ScanService;
import com.crypto.strategyservice.domain.model.PredictionResult;
import com.crypto.strategyservice.domain.service.NumericPolicy;
import com.crypto.strategyservice.infrastructure.client.fixture.FakeModelClient;
import com.crypto.strategyservice.infrastructure.client.fixture.FixtureExplanationClient;
import com.crypto.strategyservice.infrastructure.client.fixture.FixtureMarketCoveragePort;
import com.crypto.strategyservice.infrastructure.client.fixture.FixtureMarketUniversePort;
import com.crypto.strategyservice.infrastructure.client.fixture.FixtureSessionContextReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class StrategyB09IT {

    private static final Instant BASE_TIME = Instant.parse("2026-10-22T12:00:00Z");
    private static final String SCHEMA_HASH = "sha256:" + "b".repeat(64);
    private static final UUID BUNDLE_V1 = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID BUNDLE_V2 = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID FEATURE_SNAPSHOT_ID = UUID.fromString("66666666-6666-4666-8666-666666666666");

    @Autowired
    private AnalysisJobTransactions transactions;

    @Autowired
    private ScanJobRepository scanJobRepository;

    @Autowired
    private FixtureSessionContextReader sessionReader;

    @Autowired
    private FixtureMarketCoveragePort marketCoverage;

    @Autowired
    private FixtureExplanationClient explanationClient;

    @Autowired
    private FixtureMarketUniversePort universePort;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private JsonMapper json;

    private MutableClock clock;

    @BeforeEach
    void cleanDatabaseAndResetFixtures() {
        cleanTables();
        sessionReader.clear();
        marketCoverage.clear();
        explanationClient.reset();
        clock = new MutableClock(BASE_TIME);

        transactions.savePolicyArtifact(
                UUID.randomUUID(),
                new NumericPolicy.Artifact(
                        "policy-v1",
                        0.01,
                        0.95,
                        new BigDecimal("2"),
                        new BigDecimal("3"),
                        new BigDecimal("2"),
                        0.50
                )
        );
    }

    @AfterEach
    void cleanDatabaseAfterTest() {
        cleanTables();
    }

    private void cleanTables() {
        jdbc.execute("DELETE FROM scan_candidates");
        jdbc.execute("DELETE FROM scan_jobs");
        jdbc.execute("DELETE FROM backfill_requests");
        jdbc.execute("DELETE FROM proposals");
        jdbc.execute("DELETE FROM analysis_jobs");
        jdbc.execute("DELETE FROM idempotency_records");
        jdbc.execute("DELETE FROM outbox");
        jdbc.execute("DELETE FROM policy_artifacts");
    }

    @Test
    void b09_1_shouldDeserializeContractPredictionFixtureAndEnforceInvariants() throws Exception {
        Path fixturePath = Path.of("..", "..", "contracts", "fixtures", "prediction-result.json");
        if (!Files.exists(fixturePath)) {
            fixturePath = Path.of("..", "contracts", "fixtures", "prediction-result.json");
        }
        String raw = Files.readString(fixturePath, StandardCharsets.UTF_8);
        PredictionResult parsed = json.readValue(raw, PredictionResult.class);

        assertEquals(PredictionResult.Quality.INSUFFICIENT_DATA, parsed.quality());
        assertNull(parsed.predictedLogReturn());
        assertNull(parsed.volatilityEstimate());
        assertEquals(3600, parsed.horizonSeconds());
    }

    @Test
    void b09_2_fakeWaitProducesZeroProposalsAndZeroOrdersWhileReadyLongCreatesProposalAndModelTimeoutFails() {
        UUID ownerId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID instrumentId = UUID.randomUUID();

        // 1. FakeModelClient (FIXTURE_MODEL) -> SUCCEEDED / WAIT, 0 proposal
        AnalysisWorker fakeWorker = workerWithModel(new FakeModelClient());
        UUID waitJobId = fakeWorker.enqueue(ownerId, sessionId, instrumentId, 900, BASE_TIME, "idem-wait-1");
        // Aynı Idempotency-Key ile tekrar çağrı aynı jobId döndürür
        assertEquals(waitJobId, fakeWorker.enqueue(ownerId, sessionId, instrumentId, 900, BASE_TIME, "idem-wait-1"));

        assertTrue(fakeWorker.processNext());
        var waitView = transactions.findJobView(ownerId, waitJobId).orElseThrow();
        assertEquals("SUCCEEDED", waitView.status());
        assertEquals("WAIT", waitView.action());
        assertNull(waitView.proposalId());
        assertTrue(waitView.reasonCodes().contains("FIXTURE_MODEL"));
        assertEquals(0L, count("proposals"));

        // 2. READY LONG -> NumericPolicy stop=90, target=130, leverage=2 -> 1 proposal, outbox event
        ModelClient readyLongModel = new ModelClient() {
            @Override
            public Bundle activeBundle(int horizonSeconds) {
                return new Bundle(BUNDLE_V1, "features-v1", SCHEMA_HASH, List.of(60, 900, 3600), "DEVELOPMENT_CANDIDATE");
            }

            @Override
            public PredictionResult predict(Request r) {
                return new PredictionResult(
                        r.requestId(),
                        PredictionResult.Quality.READY,
                        List.of("MOMENTUM_REGIME"),
                        r.modelBundleId(),
                        r.featureVersion(),
                        SCHEMA_HASH,
                        FEATURE_SNAPSHOT_ID,
                        r.asOf(),
                        r.horizonSeconds(),
                        Math.log1p(0.05),
                        0.10,
                        "cal-v1",
                        Map.of("rsi14", 62.5, "atrPct", 0.05),
                        4
                );
            }
        };

        AnalysisWorker readyWorker = workerWithModel(readyLongModel);
        UUID longJobId = readyWorker.enqueue(ownerId, sessionId, instrumentId, 900, BASE_TIME, "idem-long-1");
        assertTrue(readyWorker.processNext());

        var longView = transactions.findJobView(ownerId, longJobId).orElseThrow();
        assertEquals("SUCCEEDED", longView.status());
        assertEquals("LONG", longView.action());
        assertNotNull(longView.proposalId());
        assertEquals("DEVELOPMENT_CANDIDATE", longView.bundleReleaseStatus());
        assertEquals("READY", longView.explanationStatus());
        assertEquals(1L, count("proposals"));

        var proposal = transactions.findProposalView(ownerId, longView.proposalId(), 1).orElseThrow();
        assertEquals("LONG", proposal.intent().side());
        assertEquals(0, new BigDecimal("90").compareTo(proposal.intent().stopLoss()));
        assertEquals(0, new BigDecimal("130").compareTo(proposal.intent().takeProfit()));
        assertEquals(0, new BigDecimal("2").compareTo(proposal.intent().suggestedLeverage()));

        // 3. Model timeout / down -> FAILED, action = null, 0 yeni proposal
        ModelClient downModel = new ModelClient() {
            @Override
            public Bundle activeBundle(int horizonSeconds) {
                return new Bundle(BUNDLE_V1, "features-v1", SCHEMA_HASH, List.of(900), "DEVELOPMENT_CANDIDATE");
            }

            @Override
            public PredictionResult predict(Request request) {
                throw new IllegalStateException("MODEL_UNAVAILABLE");
            }
        };

        AnalysisWorker failingWorker = workerWithModel(downModel);
        UUID failedJobId = failingWorker.enqueue(ownerId, sessionId, instrumentId, 900, BASE_TIME, "idem-fail-1");
        assertTrue(failingWorker.processNext());

        var failedView = transactions.findJobView(ownerId, failedJobId).orElseThrow();
        assertEquals("FAILED", failedView.status());
        assertNull(failedView.action());
        assertEquals("MODEL_UNAVAILABLE", failedView.error());
        assertEquals(1L, count("proposals"));
    }

    @Test
    void b09_3_insufficientCoverageTransitionsToWaitingDataAndTriggersSingleFlightBackfill() {
        UUID ownerId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID instrumentId = UUID.randomUUID();

        marketCoverage.givenEvaluation(instrumentId, new MarketCoveragePort.MarketEvaluation(
                UUID.randomUUID(),
                UUID.randomUUID(),
                BASE_TIME,
                0.40,
                null,
                null,
                false
        ));

        AnalysisWorker worker = workerWithModel(new FakeModelClient());
        UUID job1 = worker.enqueue(ownerId, sessionId, instrumentId, 900, BASE_TIME, "idem-cov-1");
        UUID job2 = worker.enqueue(ownerId, UUID.randomUUID(), instrumentId, 900, BASE_TIME, "idem-cov-2");

        assertTrue(worker.processNext());
        assertTrue(worker.processNext());

        var view1 = transactions.findJobView(ownerId, job1).orElseThrow();
        var view2 = transactions.findJobView(ownerId, job2).orElseThrow();
        assertEquals("WAITING_DATA", view1.status());
        assertEquals("WAITING_DATA", view2.status());

        // Aynı (instrument, horizon, asOf) aralığı için yalnız TEK backfill tetiklenmeli!
        assertEquals(1, marketCoverage.backfillCalls().size());
        assertEquals(1L, count("backfill_requests"));
    }

    @Test
    void b09_3_pinnedBundleStaysFixedAcrossReclaimAndFeatureHashMismatchFails() {
        UUID ownerId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID instrumentId = UUID.randomUUID();

        var activeBundleRef = new AtomicReference<>(
                new ModelClient.Bundle(BUNDLE_V1, "features-v1", SCHEMA_HASH, List.of(900), "DEVELOPMENT_CANDIDATE")
        );

        // İlk denemede aktif bundle V1 pinlenir ama model çağrısı sırasında ilk worker lease süresini aşar.
        // İkinci worker işi devraldığında aktif bundle V2'ye değişmiş olsa bile sabitlenmiş V1 kullanılır.
        ModelClient switchingModel = new ModelClient() {
            private int calls = 0;

            @Override
            public Bundle activeBundle(int horizonSeconds) {
                return activeBundleRef.get();
            }

            @Override
            public PredictionResult predict(Request r) {
                calls++;
                if (calls == 1) {
                    // Lease süresini geçir ve aktif bundle'ı V2'ye değiştir
                    clock.advance(Duration.ofSeconds(35));
                    activeBundleRef.set(new ModelClient.Bundle(
                            BUNDLE_V2, "features-v2", "sha256:" + "c".repeat(64), List.of(900), "DEVELOPMENT_CANDIDATE"
                    ));
                }
                return new PredictionResult(
                        r.requestId(),
                        PredictionResult.Quality.READY,
                        List.of("PINNED_BUNDLE_VERIFIED"),
                        r.modelBundleId(),
                        r.featureVersion(),
                        SCHEMA_HASH,
                        FEATURE_SNAPSHOT_ID,
                        r.asOf(),
                        r.horizonSeconds(),
                        Math.log1p(0.04),
                        0.10,
                        "cal-v1",
                        Map.of("trend", "UP"),
                        2
                );
            }
        };

        AnalysisWorker worker = workerWithModel(switchingModel);
        UUID jobId = worker.enqueue(ownerId, sessionId, instrumentId, 900, BASE_TIME, "idem-pin-1");

        // İlk worker çalışır ama predict sırasında lease süresi dolduğu için yazımı reddedilir (RUNNING kalır)
        assertTrue(worker.processNext());
        assertEquals("RUNNING", transactions.findJobView(ownerId, jobId).orElseThrow().status());

        // İkinci worker süresi dolmuş işi devralır; aktif bundle V2 olmasına rağmen pinned V1 ile tamamlar!
        assertTrue(worker.processNext());
        var completed = transactions.findJobView(ownerId, jobId).orElseThrow();
        assertEquals("SUCCEEDED", completed.status());
        assertEquals(BUNDLE_V1, completed.modelBundleId());

        // Feature schema hash uyuşmazlığı -> FAILED (FEATURE_SCHEMA_MISMATCH)
        ModelClient mismatchModel = new ModelClient() {
            @Override
            public Bundle activeBundle(int horizonSeconds) {
                return new Bundle(BUNDLE_V1, "features-v1", SCHEMA_HASH, List.of(900), "DEVELOPMENT_CANDIDATE");
            }

            @Override
            public PredictionResult predict(Request r) {
                return new PredictionResult(
                        r.requestId(),
                        PredictionResult.Quality.READY,
                        List.of(),
                        r.modelBundleId(),
                        r.featureVersion(),
                        "sha256:" + "f".repeat(64),
                        FEATURE_SNAPSHOT_ID,
                        r.asOf(),
                        r.horizonSeconds(),
                        0.05,
                        0.10,
                        "cal-v1",
                        Map.of(),
                        1
                );
            }
        };

        AnalysisWorker mismatchWorker = workerWithModel(mismatchModel);
        UUID mismatchJobId = mismatchWorker.enqueue(ownerId, sessionId, instrumentId, 900, BASE_TIME, "idem-mismatch-1");
        assertTrue(mismatchWorker.processNext());

        var mismatchView = transactions.findJobView(ownerId, mismatchJobId).orElseThrow();
        assertEquals("FAILED", mismatchView.status());
        assertEquals("FEATURE_SCHEMA_MISMATCH", mismatchView.error());
        assertNull(mismatchView.action());
    }

    @Test
    void b09_7_explanationTimeoutFallsBackToTemplateWithoutExtendingProposalTtl() {
        UUID ownerId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID instrumentId = UUID.randomUUID();

        // Açıklama servisi 12 saniye gecikip timeout fırlatsa bile proposal validUntil
        // kararın hazır olduğu (readyAt = BASE_TIME) andan itibaren +60s olmalı (uzamamalı)!
        explanationClient.setBehavior((ExplanationClient.Request req) -> {
            clock.advance(Duration.ofSeconds(12));
            throw new IllegalStateException("EXPLANATION_TIMEOUT");
        });

        ModelClient readyModel = new ModelClient() {
            @Override
            public Bundle activeBundle(int horizonSeconds) {
                return new Bundle(BUNDLE_V1, "features-v1", SCHEMA_HASH, List.of(900), "DEVELOPMENT_CANDIDATE");
            }

            @Override
            public PredictionResult predict(Request r) {
                return new PredictionResult(
                        r.requestId(),
                        PredictionResult.Quality.READY,
                        List.of("VOLATILITY_OK", "MOMENTUM_STRONG"),
                        r.modelBundleId(),
                        r.featureVersion(),
                        SCHEMA_HASH,
                        FEATURE_SNAPSHOT_ID,
                        r.asOf(),
                        r.horizonSeconds(),
                        Math.log1p(0.05),
                        0.10,
                        "cal-v1",
                        Map.of("emaCross", "BULLISH", "atr14", "5.00"),
                        1
                );
            }
        };

        AnalysisWorker worker = workerWithModel(readyModel);
        UUID jobId = worker.enqueue(ownerId, sessionId, instrumentId, 900, BASE_TIME, "idem-exp-timeout");
        assertTrue(worker.processNext());

        var view = transactions.findJobView(ownerId, jobId).orElseThrow();
        assertEquals("SUCCEEDED", view.status());
        assertEquals("LONG", view.action());
        assertEquals("TEMPLATE", view.explanationStatus());
        assertTrue(view.explanation().startsWith("TEMPLATE[action=LONG;policy=NUMERIC_POLICY;"));
        assertTrue(view.explanation().contains("VOLATILITY_OK,MOMENTUM_STRONG,NUMERIC_POLICY"));
        assertTrue(view.explanation().contains("atr14=5.00,emaCross=BULLISH"));

        var proposal = transactions.findProposalView(ownerId, view.proposalId(), 1).orElseThrow();
        // TTL, açıklama 12 saniye sürse bile BASE_TIME + 60s olarak kalır (BASE_TIME + 72s olmaz!)
        assertEquals(BASE_TIME.plusSeconds(60), proposal.validUntil());
    }

    @Test
    void b09_6_scanSupportsOverTwoHundredUniverseViaCursorAndPartialBudgetAndCancelStopsScheduling() {
        UUID ownerId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        // 250 adaylı (sayfa boyutu 200 sınırını aşan) bir piyasa evreni oluştur
        List<MarketUniversePort.Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            boolean eligible = (i % 5 != 0); // Her 5 adaydan 1'i INSUFFICIENT_COVERAGE ile atlanır
            candidates.add(new MarketUniversePort.Candidate(
                    UUID.randomUUID(),
                    eligible,
                    eligible ? null : "INSUFFICIENT_COVERAGE"
            ));
        }
        universePort.setSnapshot(new MarketUniversePort.UniverseSnapshot("universe-250-v1", candidates));

        ScanService scanService = new ScanService(scanJobRepository, universePort, tx, clock);

        // Sayfa boyutu > 200 reddedilmeli
        UUID scanId = scanService.createScan(ownerId, sessionId, 900, null);
        assertThrows(
                IllegalArgumentException.class,
                () -> scanService.processPage(ownerId, scanId, 201, 200, BASE_TIME)
        );

        // 1. Adım: pageLimit=200 fakat analiz bütçesi=10 -> Bütçe bitince PARTIAL ve cursor dönmeli ("tüm piyasa tarandı" dememeli!)
        var partialByBudget = scanService.processPage(ownerId, scanId, 200, 10, BASE_TIME);
        assertEquals("PARTIAL", partialByBudget.status());
        assertNotNull(partialByBudget.cursor());
        assertEquals(10L, partialByBudget.evaluatedCount());
        assertEquals(3L, partialByBudget.skippedCount()); // indeks 0..12 arasında 0, 5, 10 atlandı (3 adet), 10 adet eligible işlendi
        assertEquals(3L, partialByBudget.reasonCounts().get("INSUFFICIENT_COVERAGE"));
        assertEquals(10L, count("analysis_jobs"));

        // 2. Adım: Cancel çağrıldığında PARTIAL -> CANCELLED olur ve yeni analiz planlamaz
        assertTrue(scanService.cancel(ownerId, scanId));
        var afterCancel = scanService.processPage(ownerId, scanId, 200, 200, BASE_TIME);
        assertEquals("CANCELLED", afterCancel.status());
        assertEquals(10L, count("analysis_jobs")); // Yeni analysis_job eklenmedi!

        // 3. Adım: 250 adaylık evreni 200'lük sayfalarla sonuna kadar tarayıp SUCCEEDED olma testi
        UUID fullScanId = scanService.createScan(ownerId, sessionId, 900, null);
        var page1 = scanService.processPage(ownerId, fullScanId, 200, 200, BASE_TIME);
        assertEquals("PARTIAL", page1.status());
        assertEquals("cursor:universe-250-v1:200", page1.cursor());
        assertEquals(160L, page1.evaluatedCount());
        assertEquals(40L, page1.skippedCount());

        var page2 = scanService.processPage(ownerId, fullScanId, 200, 200, BASE_TIME);
        assertEquals("SUCCEEDED", page2.status());
        assertNull(page2.cursor());
        assertEquals(200L, page2.evaluatedCount());
        assertEquals(50L, page2.skippedCount());
        assertEquals(250, page2.candidates().size());
    }

    private AnalysisWorker workerWithModel(ModelClient model) {
        return new AnalysisWorker(
                transactions,
                model,
                sessionReader,
                marketCoverage,
                explanationClient,
                clock,
                30L,
                60L,
                "policy-v1"
        );
    }

    private long count(String table) {
        Long c = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return c != null ? c : 0L;
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant initial) {
            this.current = initial;
        }

        void advance(Duration duration) {
            this.current = this.current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
