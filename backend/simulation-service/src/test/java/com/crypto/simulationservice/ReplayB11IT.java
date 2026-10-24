package com.crypto.simulationservice;

import com.crypto.financecore.finance.Decimal;
import com.crypto.financecore.finance.PositionSide;
import com.crypto.simulationservice.application.replay.ReplayScope;
import com.crypto.simulationservice.application.replay.ReplayService;
import com.crypto.simulationservice.domain.exception.BusinessException;
import com.crypto.simulationservice.domain.model.replay.FidelityReport;
import com.crypto.simulationservice.domain.model.replay.ReplayInput;
import com.crypto.simulationservice.domain.service.replay.BarAmbiguity;
import com.crypto.simulationservice.infrastructure.artifact.ArtifactReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ReplayB11IT {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private ReplayService replayService;

    @TempDir
    Path tempDir;

    @Test
    void b11_1_and_b11_2_twoRunsWithSameFixtureSeedAndPolicyProduceIdenticalEconomicChecksum() {
        UUID ownerId = UUID.randomUUID();
        UUID datasetId = UUID.randomUUID();
        UUID ruleId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();

        var spec = new ReplayService.ReplaySpec(
                datasetId,
                ruleId,
                policyId,
                42L,
                "sha256:" + "a".repeat(64),
                "sha256:" + "b".repeat(64),
                "sha256:" + "c".repeat(64),
                "TIE_POLICY_V1",
                "RECORDED_TICKS",
                new BigDecimal("10000"),
                START
        );

        List<ReplayInput> journalInputs = List.of(
                new ReplayInput(
                        UUID.fromString("10000000-0000-4000-8000-000000000001"),
                        START.plusSeconds(60),
                        ReplayInput.Kind.CANDLE_CLOSE,
                        1L,
                        "{}"
                ),
                new ReplayInput(
                        UUID.fromString("10000000-0000-4000-8000-000000000002"),
                        START.plusSeconds(61),
                        ReplayInput.Kind.ENTRY_FILL,
                        2L,
                        """
                        {"side":"LONG","marginBudget":"1000","leverage":"10","entryPrice":"50000","step":"0.001","feeRate":"0.0005","stopLoss":"48000","takeProfit":"55000","signalTime":"2026-01-01T00:01:00Z"}
                        """
                ),
                new ReplayInput(
                        UUID.fromString("10000000-0000-4000-8000-000000000003"),
                        START.plusSeconds(120),
                        ReplayInput.Kind.FUNDING_SETTLEMENT,
                        3L,
                        """
                        {"markPrice":"50000","fundingRate":"0.0001"}
                        """
                ),
                new ReplayInput(
                        UUID.fromString("10000000-0000-4000-8000-000000000004"),
                        START.plusSeconds(180),
                        ReplayInput.Kind.QUOTE_EXIT,
                        4L,
                        """
                        {"exitPrice":"50500","feeRate":"0.0005","reason":"CLOSE_FILL"}
                        """
                )
        );

        UUID replay1 = replayService.createReplayJob(ownerId, "idem-replay-1", spec);
        // Aynı Idempotency-Key tekrarı aynı replayId döndürür
        assertEquals(replay1, replayService.createReplayJob(ownerId, "idem-replay-1", spec));

        UUID replay2 = replayService.createReplayJob(ownerId, "idem-replay-2", spec);

        var res1 = replayService.executeReplay(ownerId, replay1, journalInputs, true, false, false, false, () -> false);
        var res2 = replayService.executeReplay(ownerId, replay2, journalInputs, true, false, false, false, () -> false);

        assertEquals("SUCCEEDED", res1.status());
        assertEquals("EXACT_WITHIN_MODEL", res1.fidelity());
        assertEquals(res1.economicChecksum(), res2.economicChecksum());
        assertEquals(0, new BigDecimal("100").compareTo(res1.grossPnl()));
        assertEquals(0, new BigDecimal("10.05").compareTo(res1.totalFees()));
        assertEquals(0, new BigDecimal("-1").compareTo(res1.signedFunding()));
        assertEquals(0, new BigDecimal("88.95").compareTo(res1.netPnl()));
        assertEquals(0, new BigDecimal("10088.95").compareTo(res1.finalAvailable()));

        // Başka kullanıcı CSV veya sonucu okuyamaz
        assertThrows(
                BusinessException.class,
                () -> replayService.exportCsv(UUID.randomUUID(), replay1)
        );
        assertTrue(replayService.exportCsv(ownerId, replay1).contains(res1.economicChecksum()));
    }

    @Test
    void b11_3_and_b11_4_sameBarAmbiguityAdverseFirstLookaheadPreventionAndScopeIsolation() {
        // 1. Aynı mumda hem SL hem TP görülürse adverse-first (STOP_LOSS) ve ambiguous=true
        var bothHitLong = BarAmbiguity.bracket(
                PositionSide.LONG,
                Decimal.parse("47500"),
                Decimal.parse("55500"),
                Decimal.parse("48000"),
                Decimal.parse("55000")
        );
        assertEquals("STOP_LOSS", bothHitLong.reason());
        assertTrue(bothHitLong.ambiguous());

        var bothHitShort = BarAmbiguity.bracket(
                PositionSide.SHORT,
                Decimal.parse("44500"),
                Decimal.parse("52500"),
                Decimal.parse("52000"),
                Decimal.parse("45000")
        );
        assertEquals("STOP_LOSS", bothHitShort.reason());
        assertTrue(bothHitShort.ambiguous());

        // 2. Lookahead engeli: mum kapanışı t'de üretilen sinyal t anında (aynı mum içinde) fill olamaz!
        Instant barClose = Instant.parse("2026-01-01T00:15:00Z");
        assertEquals(
                "SAME_BAR_LOOKAHEAD_FILL_FORBIDDEN",
                assertThrows(
                        IllegalArgumentException.class,
                        () -> BarAmbiguity.verifyNoLookahead(barClose, barClose, barClose)
                ).getMessage()
        );
        assertEquals(
                "LOOKAHEAD_FEATURE_NOT_YET_AVAILABLE",
                assertThrows(
                        IllegalArgumentException.class,
                        () -> BarAmbiguity.verifyNoLookahead(barClose.plusSeconds(60), barClose, barClose.plusSeconds(1))
                ).getMessage()
        );

        // 3. ReplayScope: LIVE_PAPER modunda oluşturulamaz ve başka session'a emir açamaz
        assertEquals(
                "REPLAY_SCOPE_REQUIRED",
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new ReplayScope(UUID.randomUUID(), UUID.randomUUID(), "LIVE_PAPER", UUID.randomUUID())
                ).getMessage()
        );

        UUID runId = UUID.randomUUID();
        UUID replaySessionId = UUID.randomUUID();
        ReplayScope scope = new ReplayScope(runId, replaySessionId, "HISTORICAL_REPLAY", UUID.randomUUID());
        assertEquals(
                "REPLAY_SCOPE_MISMATCH",
                assertThrows(
                        IllegalArgumentException.class,
                        () -> scope.verify(UUID.randomUUID(), runId)
                ).getMessage()
        );

        // 4. PAIRED_OHLC + same-bar ambiguity -> APPROXIMATE fidelity & ambiguousBarCount = 1
        UUID ownerId = UUID.randomUUID();
        var spec = new ReplayService.ReplaySpec(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 99L,
                "sha256:" + "1".repeat(64), "sha256:" + "2".repeat(64), "sha256:" + "3".repeat(64),
                "TIE_POLICY_V1", "PAIRED_OHLC", new BigDecimal("10000"), START
        );
        UUID replayId = replayService.createReplayJob(ownerId, "idem-ambig", spec);
        List<ReplayInput> ambigInputs = List.of(
                new ReplayInput(
                        UUID.randomUUID(),
                        START.plusSeconds(10),
                        ReplayInput.Kind.ENTRY_FILL,
                        1L,
                        """
                        {"side":"LONG","marginBudget":"1000","leverage":"10","entryPrice":"50000","step":"0.001","feeRate":"0.0005","stopLoss":"48000","takeProfit":"55000"}
                        """
                ),
                new ReplayInput(
                        UUID.randomUUID(),
                        START.plusSeconds(60),
                        ReplayInput.Kind.BRACKET,
                        2L,
                        """
                        {"markLow":"47500","markHigh":"56000","feeRate":"0.0005"}
                        """
                )
        );
        var ambigResult = replayService.executeReplay(ownerId, replayId, ambigInputs, false, false, false, false, () -> false);
        assertEquals("SUCCEEDED", ambigResult.status());
        assertEquals("APPROXIMATE", ambigResult.fidelity());
        assertEquals(1L, ambigResult.ambiguousBarCount());
        // Adverse-first nedeniyle TP (+1000) değil SL (-400) uygulandı
        assertEquals(0, new BigDecimal("-400").compareTo(ambigResult.grossPnl()));
    }

    @Test
    void b11_5_and_b11_6_artifactReaderHashVerificationAndCancelledReplayDoesNotPublishSucceeded() throws Exception {
        // 1. ArtifactReader doğrulaması: hash uyuşmazlığı ve path traversal reddi
        Path artifactsRoot = Files.createDirectories(tempDir.resolve("artifacts"));
        Path jsonlFile = artifactsRoot.resolve("inputs.jsonl");
        Path outsideFile = tempDir.resolve("outside.jsonl");
        String content = """
                {"id":"10000000-0000-4000-8000-000000000001","time":"2026-01-01T00:01:00Z","kind":"MARK","originalSequence":1,"payload":{}}
                """;
        Files.writeString(jsonlFile, content, StandardCharsets.UTF_8);
        Files.writeString(outsideFile, content, StandardCharsets.UTF_8);

        MessageDigest md = MessageDigest.getInstance("SHA-256");
        String expectedHash = "sha256:" + HexFormat.of().formatHex(md.digest(Files.readAllBytes(jsonlFile)));

        ArtifactReader reader = new ArtifactReader(artifactsRoot);
        Path verifiedPath = reader.verified("inputs.jsonl", expectedHash, 1024 * 1024);
        List<ReplayInput> parsed = replayService.readJsonlInputs(verifiedPath);
        assertEquals(1, parsed.size());

        assertEquals(
                "ARTIFACT_HASH_MISMATCH",
                assertThrows(
                        IllegalArgumentException.class,
                        () -> reader.verified("inputs.jsonl", "sha256:" + "0".repeat(64), 1024 * 1024)
                ).getMessage()
        );
        assertEquals(
                "INVALID_ARTIFACT",
                assertThrows(
                        IllegalArgumentException.class,
                        () -> reader.verified("../outside.jsonl", expectedHash, 1024 * 1024)
                ).getMessage()
        );

        // 2. Cancel edilen replay SUCCEEDED olarak sunulmaz ve resultArtifactId = null kalır
        UUID ownerId = UUID.randomUUID();
        var spec = new ReplayService.ReplaySpec(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 7L,
                expectedHash, "sha256:" + "2".repeat(64), "sha256:" + "3".repeat(64),
                "TIE_POLICY_V1", "RECORDED_TICKS", new BigDecimal("10000"), START
        );
        UUID cancelledReplayId = replayService.createReplayJob(ownerId, "idem-cancel-replay", spec);
        assertTrue(replayService.cancelReplay(ownerId, cancelledReplayId, START.plusSeconds(1)));

        var cancelledView = replayService.executeReplay(
                ownerId, cancelledReplayId, parsed, true, false, false, false, () -> false
        );
        assertEquals("CANCELLED", cancelledView.status());
        assertNull(cancelledView.resultArtifactId());

        // 3. TRADE_ONLY katmanında missingMark=true -> INDETERMINATE
        FidelityReport tradeOnly = FidelityReport.classify("TRADE_ONLY", false, true, 0, false);
        assertEquals("INDETERMINATE", tradeOnly.fidelity());
    }
}
