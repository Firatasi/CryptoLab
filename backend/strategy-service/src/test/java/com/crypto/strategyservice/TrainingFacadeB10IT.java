package com.crypto.strategyservice;

import com.crypto.strategyservice.api.controller.TrainingController;
import com.crypto.strategyservice.api.controller.TrainingJsonController;
import com.crypto.strategyservice.api.exception.StrategyApiErrors;
import com.crypto.strategyservice.application.repository.AnalysisJobRepository;
import com.crypto.strategyservice.application.repository.TrainingJobProjectionRepository;
import com.crypto.strategyservice.application.service.AnalysisJobTransactions;
import com.crypto.strategyservice.application.service.AnalysisWorker;
import com.crypto.strategyservice.domain.model.PredictionResult;
import com.crypto.strategyservice.infrastructure.client.fixture.FakeTrainingProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles({"test", "fixture"})
class TrainingFacadeB10IT {

    private static final Instant NOW = Instant.parse("2026-10-22T16:00:00Z");
    private static final UUID USER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID TRAINER_A_ID = UUID.fromString("20000000-0000-4000-8000-000000000002");
    private static final UUID TRAINER_B_ID = UUID.fromString("30000000-0000-4000-8000-000000000003");
    private static final UUID PUBLISHER_ID = UUID.fromString("40000000-0000-4000-8000-000000000004");

    @Autowired
    private TrainingController trainingController;

    @Autowired
    private TrainingJsonController trainingJsonController;

    @Autowired
    private FakeTrainingProvider fakeTrainingProvider;

    @Autowired
    private TrainingJobProjectionRepository projectionRepository;

    @Autowired
    private AnalysisWorker analysisWorker;

    @Autowired
    private AnalysisJobTransactions transactions;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JsonMapper json;

    private final StrategyApiErrors apiErrors = new StrategyApiErrors();

    @BeforeEach
    void setUp() {
        cleanTables();
        fakeTrainingProvider.reset();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        fakeTrainingProvider.reset();
        cleanTables();
    }

    private void cleanTables() {
        jdbc.execute("DELETE FROM training_job_projection");
        jdbc.execute("DELETE FROM inbox");
        jdbc.execute("DELETE FROM scan_candidates");
        jdbc.execute("DELETE FROM scan_jobs");
        jdbc.execute("DELETE FROM backfill_requests");
        jdbc.execute("DELETE FROM proposals");
        jdbc.execute("DELETE FROM analysis_jobs");
        jdbc.execute("DELETE FROM idempotency_records");
        jdbc.execute("DELETE FROM outbox");
    }

    @Test
    void b10_2_userIsForbiddenOnAllRoutesAndTrainerWithoutModelPublishCannotPromoteOrRollback() {
        Jwt userJwt = authenticate(USER_ID, List.of("USER"), List.of());

        assertThrows(
                AccessDeniedException.class,
                () -> trainingJsonController.sources(userJwt, Map.of())
        );
        assertThrows(
                AccessDeniedException.class,
                () -> trainingJsonController.models(userJwt, Map.of())
        );
        assertThrows(
                AccessDeniedException.class,
                () -> trainingController.promote(
                        userJwt,
                        UUID.randomUUID(),
                        "idem-user-promote",
                        json.readTree("{\"expectedAliasVersion\":1,\"evaluationId\":\"" + UUID.randomUUID() + "\"}")
                )
        );

        // TRAINER rolü kaynak listeleyebilir fakat MODEL_PUBLISH yetkisi yoksa promote/rollback 403 alır
        Jwt trainerJwt = authenticate(TRAINER_A_ID, List.of("TRAINER"), List.of());
        ResponseEntity<byte[]> sourcesResp = trainingJsonController.sources(trainerJwt, Map.of());
        assertEquals(200, sourcesResp.getStatusCode().value());

        AccessDeniedException promoteDenied = assertThrows(
                AccessDeniedException.class,
                () -> trainingController.promote(
                        trainerJwt,
                        UUID.randomUUID(),
                        "idem-trainer-promote",
                        json.readTree("{\"expectedAliasVersion\":1,\"evaluationId\":\"" + UUID.randomUUID() + "\"}")
                )
        );
        assertEquals(403, apiErrors.accessDenied(promoteDenied).getStatusCode().value());

        assertThrows(
                AccessDeniedException.class,
                () -> trainingController.rollback(
                        trainerJwt,
                        UUID.randomUUID(),
                        "idem-trainer-rollback",
                        json.readTree("{\"expectedAliasVersion\":1,\"reason\":\"regression\"}")
                )
        );
    }

    @Test
    void b10_3_and_b10_4_uploadIdempotencyOwnerIsolationOversizedPayloadAndCancelLifecycle() throws Exception {
        Jwt trainerA = authenticate(TRAINER_A_ID, List.of("TRAINER"), List.of());

        // 1. 20MB üzeri dosya yükleme -> UPLOAD_SIZE -> 413 PAYLOAD_TOO_LARGE
        MockMultipartFile oversizedFile = new MockMultipartFile(
                "file",
                "big.pdf",
                "application/pdf",
                new byte[]{1, 2, 3}
        ) {
            @Override
            public long getSize() {
                return 20L * 1024 * 1024 + 1;
            }
        };
        IllegalArgumentException sizeEx = assertThrows(
                IllegalArgumentException.class,
                () -> trainingController.upload(trainerA, "idem-big", oversizedFile, "Title", "Author", "Rights")
        );
        assertEquals(413, apiErrors.badRequest(sizeEx).getStatusCode().value());

        // 2. Geçerli upload -> 202 SourceJob; aynı Idempotency-Key + aynı içerik -> aynı jobId; farklı içerik -> 409
        MockMultipartFile validPdf = new MockMultipartFile(
                "file",
                "book.pdf",
                "application/pdf",
                "%PDF-1.7 valid bytes".getBytes(StandardCharsets.UTF_8)
        );
        ResponseEntity<byte[]> firstUpload = trainingController.upload(
                trainerA, "idem-src-1", validPdf, "Order Flow Book", "Author X", "Internal educational rights"
        );
        assertEquals(202, firstUpload.getStatusCode().value());
        JsonNode firstBody = json.readTree(firstUpload.getBody());
        String sourceId = firstBody.get("sourceId").asString();
        String jobId = firstBody.get("jobId").asString();
        assertEquals("FIXTURE_TRAINING_PROVIDER", firstBody.get("providerMode").asString());

        ResponseEntity<byte[]> duplicateUpload = trainingController.upload(
                trainerA, "idem-src-1", validPdf, "Order Flow Book", "Author X", "Internal educational rights"
        );
        assertEquals(202, duplicateUpload.getStatusCode().value());
        assertEquals(jobId, json.readTree(duplicateUpload.getBody()).get("jobId").asString());

        ResponseEntity<byte[]> conflictingUpload = trainingController.upload(
                trainerA, "idem-src-1", validPdf, "Different Title", "Author X", "Internal educational rights"
        );
        assertEquals(409, conflictingUpload.getStatusCode().value());

        // 3. Cross-user source izolasyonu: Trainer B, Trainer A'nın kaynağına erişemez veya silemez
        Jwt trainerB = authenticate(TRAINER_B_ID, List.of("TRAINER"), List.of());
        ResponseEntity<byte[]> otherUserGet = trainingJsonController.source(
                trainerB, UUID.fromString(sourceId), Map.of()
        );
        assertEquals(404, otherUserGet.getStatusCode().value());

        ResponseEntity<byte[]> otherUserPage = trainingController.sourcePage(
                trainerB, UUID.fromString(sourceId), 1
        );
        assertEquals(404, otherUserPage.getStatusCode().value());

        // Trainer A kendi sayfasını önizleyebilir
        Jwt trainerAAgain = authenticate(TRAINER_A_ID, List.of("TRAINER"), List.of());
        ResponseEntity<byte[]> ownerPage = trainingController.sourcePage(
                trainerAAgain, UUID.fromString(sourceId), 1
        );
        assertEquals(200, ownerPage.getStatusCode().value());

        // 4. ContractGate: tanımsız alan (ör. pdf/unknown) içeren JSON isteği reddedilir
        assertEquals(
                "UNKNOWN_FIELD",
                assertThrows(
                        IllegalArgumentException.class,
                        () -> trainingJsonController.run(
                                trainerAAgain,
                                "idem-bad-run",
                                "{\"taskType\":\"NUMERIC\",\"datasetId\":\"11111111-1111-4111-8111-111111111111\",\"config\":{},\"seed\":42,\"pdf\":\"inline\"}".getBytes(StandardCharsets.UTF_8)
                        )
                ).getMessage()
        );

        // 5. Run başlatma ve durable cancel (202)
        byte[] runReq = ("{\"taskType\":\"NUMERIC\",\"datasetId\":\"" + UUID.randomUUID() + "\",\"config\":{\"lr\":0.001},\"seed\":42}")
                .getBytes(StandardCharsets.UTF_8);
        ResponseEntity<byte[]> runCreated = trainingJsonController.run(trainerAAgain, "idem-run-ok", runReq);
        assertEquals(202, runCreated.getStatusCode().value());
        UUID runId = UUID.fromString(json.readTree(runCreated.getBody()).get("runId").asString());

        ResponseEntity<byte[]> runCancelled = trainingJsonController.cancel(
                trainerAAgain,
                runId,
                "idem-cancel-ok",
                "{\"reason\":\"trainer requested stop\"}".getBytes(StandardCharsets.UTF_8)
        );
        assertEquals(202, runCancelled.getStatusCode().value());
        assertEquals("CANCELLED", json.readTree(runCancelled.getBody()).get("status").asString());
    }

    @Test
    void b10_5_projectionDeduplicatesEventsKeepsStageAndStatusDistinctAndDoesNotFabricateProgress() {
        UUID jobId = UUID.randomUUID();
        UUID event1 = UUID.randomUUID();

        // Metadata/example extraction SUCCEEDED olsa bile stage=EXTRACTING olduğu için model eğitilmiş sayılmaz!
        // Progress null geldiğinde yüzde uydurulmaz, null saklanır.
        var extractSucceeded = new TrainingJobProjectionRepository.JobProjectionEvent(
                event1,
                jobId,
                "SUCCEEDED",
                "EXTRACTING",
                null,
                1L,
                NOW
        );

        assertTrue(projectionRepository.applyEvent(extractSucceeded));
        // Aynı eventId tekrar geldiğinde inbox dedupe devreye girer
        assertFalse(projectionRepository.applyEvent(extractSucceeded));

        var view1 = projectionRepository.findById(jobId).orElseThrow();
        assertEquals("SUCCEEDED", view1.status());
        assertEquals("EXTRACTING", view1.stage());
        assertNull(view1.progress());
        assertFalse(view1.isModelTrained());

        // Daha eski timestamp'li bir olay yeni durumu ezemez
        var staleEvent = new TrainingJobProjectionRepository.JobProjectionEvent(
                UUID.randomUUID(),
                jobId,
                "RUNNING",
                "INGESTING",
                Map.of("step", 1),
                1L,
                NOW.minusSeconds(10)
        );
        assertFalse(projectionRepository.applyEvent(staleEvent));
        assertEquals("EXTRACTING", projectionRepository.findById(jobId).orElseThrow().stage());
    }

    @Test
    void b10_6_and_b10_7_promotionEnforcesAliasVersionCasPreservesHistoricalProposalsAndOutageDoesNotBreakInference() {
        // Önceden oluşturulmuş bir proposal'ın modelBundleId ve featureSnapshotId geçmişini kaydet
        UUID ownerId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID instrumentId = UUID.randomUUID();
        UUID originalBundleId = UUID.fromString("44444444-4444-4444-8444-444444444444");
        UUID originalFeatureSnapshotId = UUID.fromString("66666666-6666-4666-8666-666666666666");
        UUID existingProposalId = UUID.randomUUID();

        UUID jobId = transactions.enqueueIdempotent(
                ownerId, sessionId, instrumentId, 900, NOW, "idem-pre-prop", NOW
        );
        var claimed = transactions.claimNext(NOW, java.time.Duration.ofSeconds(30)).orElseThrow();
        transactions.pinContext(claimed, new AnalysisJobRepository.PinnedContext(
                1L,
                UUID.randomUUID(),
                UUID.randomUUID(),
                originalBundleId,
                "features-v1",
                "sha256:" + "b".repeat(64),
                "DEVELOPMENT_CANDIDATE"
        ), NOW);
        var readyPred = new PredictionResult(
                jobId,
                PredictionResult.Quality.READY,
                List.of("HISTORICAL_EVIDENCE"),
                originalBundleId,
                "features-v1",
                "sha256:" + "b".repeat(64),
                originalFeatureSnapshotId,
                NOW,
                900,
                0.04,
                0.10,
                "cal-v1",
                Map.of(),
                1
        );
        transactions.completeProposal(
                claimed,
                readyPred,
                new AnalysisJobRepository.ProposalDraft(
                        existingProposalId,
                        "policy-v1",
                        new AnalysisJobRepository.ProposalIntent(
                                "LONG",
                                "MARKET",
                                null,
                                new BigDecimal("90"),
                                new BigDecimal("130"),
                                new BigDecimal("2"),
                                900,
                                "CLOSE_AT_DEADLINE",
                                new BigDecimal("100"),
                                new BigDecimal("5")
                        ),
                        NOW.plusSeconds(60),
                        List.of("HISTORICAL_EVIDENCE"),
                        "READY",
                        "Historical explanation"
                ),
                NOW
        );

        // TRAINER + MODEL_PUBLISH ile yeni modeli promote et
        Jwt publisherJwt = authenticate(PUBLISHER_ID, List.of("TRAINER"), List.of("MODEL_PUBLISH"));
        UUID newModelId = UUID.randomUUID();
        UUID evaluationId = UUID.randomUUID();

        ResponseEntity<byte[]> promoted = trainingController.promote(
                publisherJwt,
                newModelId,
                "idem-promote-1",
                json.readTree("{\"expectedAliasVersion\":1,\"evaluationId\":\"" + evaluationId + "\"}")
        );
        assertEquals(200, promoted.getStatusCode().value());
        assertEquals(2L, fakeTrainingProvider.activeAliasVersion());
        assertEquals(1, fakeTrainingProvider.audits().size());

        // Eski expectedAliasVersion (1) ile başka bir promote denemesi 409 ALIAS_VERSION_CONFLICT döner
        ResponseEntity<byte[]> stalePromote = trainingController.promote(
                publisherJwt,
                UUID.randomUUID(),
                "idem-promote-stale",
                json.readTree("{\"expectedAliasVersion\":1,\"evaluationId\":\"" + UUID.randomUUID() + "\"}")
        );
        assertEquals(409, stalePromote.getStatusCode().value());

        // Eski proposal'ın modelBundleId ve featureSnapshotId değerleri ASLA değişmemiştir!
        var preservedProposal = transactions.findProposalView(ownerId, existingProposalId, 1).orElseThrow();
        assertEquals(originalBundleId, preservedProposal.modelBundleId());
        assertEquals(originalFeatureSnapshotId, preservedProposal.featureSnapshotId());

        // B10.7: Eğitim servisi kapandığında training route 503 döner, fakat hazır model analizi çalışmaya devam eder!
        fakeTrainingProvider.setAvailable(false);
        IllegalStateException unavailableEx = assertThrows(
                IllegalStateException.class,
                () -> trainingJsonController.models(publisherJwt, Map.of())
        );
        var errorResponse = apiErrors.illegalState(unavailableEx);
        assertEquals(503, errorResponse.getStatusCode().value());
        assertEquals(Boolean.TRUE, errorResponse.getBody().get("retryable"));

        // Hazır model analizi (AnalysisWorker) eğitim servisi kapalıyken etkilenmeden çalışır
        UUID independentAnalysisId = analysisWorker.enqueue(
                ownerId, sessionId, instrumentId, 900, NOW, "idem-independent-analysis"
        );
        assertTrue(analysisWorker.processNext());
        var analysisView = transactions.findJobView(ownerId, independentAnalysisId).orElseThrow();
        assertEquals("SUCCEEDED", analysisView.status());
    }

    private static Jwt authenticate(UUID subject, List<String> roles, List<String> authorities) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .subject(subject.toString())
                .claim("roles", roles)
                .claim("authorities", authorities)
                .build();

        var granted = new ArrayList<SimpleGrantedAuthority>();
        roles.forEach(r -> granted.add(new SimpleGrantedAuthority("ROLE_" + r)));
        authorities.forEach(a -> granted.add(new SimpleGrantedAuthority(a)));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(jwt, "N/A", granted)
        );
        return jwt;
    }
}
