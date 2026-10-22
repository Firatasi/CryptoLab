package com.crypto.simulationservice.application.replay;

import com.crypto.financecore.finance.Decimal;
import com.crypto.financecore.finance.Finance;
import com.crypto.financecore.finance.PositionSide;
import com.crypto.simulationservice.application.repository.LedgerRepository;
import com.crypto.simulationservice.domain.exception.BusinessException;
import com.crypto.simulationservice.domain.model.Journal;
import com.crypto.simulationservice.domain.model.replay.FidelityReport;
import com.crypto.simulationservice.domain.model.replay.ReplayInput;
import com.crypto.simulationservice.domain.service.FillAccounting;
import com.crypto.simulationservice.domain.service.replay.BarAmbiguity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Sorumluluk: HISTORICAL_REPLAY oturumlarını oluşturur, manifest hash ve seed değerlerini
 * dondurur, ReplayRunner ve VirtualClock ile aynı Java finans motoru (Finance + FillAccounting + LedgerRepository)
 * üzerinden deterministik çalıştırır ve ekonomik sağlama toplamını (economicChecksum) üretir.
 */
@Service
public class ReplayService {

    public record ReplaySpec(
            UUID datasetId,
            UUID ruleProfileId,
            UUID policyArtifactId,
            long seed,
            String datasetHash,
            String ruleHash,
            String policyHash,
            String tiePolicy,
            String dataLayer,
            BigDecimal initialDeposit,
            Instant startTime
    ) {
        public ReplaySpec {
            if (datasetId == null || ruleProfileId == null || policyArtifactId == null
                    || datasetHash == null || datasetHash.isBlank()
                    || ruleHash == null || ruleHash.isBlank()
                    || policyHash == null || policyHash.isBlank()
                    || tiePolicy == null || tiePolicy.isBlank()
                    || dataLayer == null || dataLayer.isBlank()
                    || initialDeposit == null || initialDeposit.signum() <= 0
                    || startTime == null) {
                throw new IllegalArgumentException("INVALID_REPLAY_SPEC");
            }
        }
    }

    public record ReplayResultView(
            UUID replayId,
            UUID ownerId,
            UUID sessionId,
            String status,
            String dataLayer,
            String fidelity,
            long ambiguousBarCount,
            String datasetHash,
            String ruleHash,
            String policyHash,
            long seed,
            UUID resultArtifactId,
            BigDecimal grossPnl,
            BigDecimal netPnl,
            BigDecimal totalFees,
            BigDecimal signedFunding,
            BigDecimal liquidationFees,
            BigDecimal insuranceAdjustment,
            BigDecimal finalAvailable,
            String economicChecksum,
            String csvPayload
    ) {
    }

    private final JdbcTemplate jdbc;
    private final LedgerRepository ledger;
    private final ObjectMapper json;

    public ReplayService(JdbcTemplate jdbc, LedgerRepository ledger, ObjectMapper json) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.json = json;
    }

    @Transactional
    public UUID createReplayJob(
            UUID ownerId,
            String idempotencyKey,
            ReplaySpec spec
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new IllegalArgumentException("INVALID_KEY");
        }
        String route = "POST /api/v1/sim/replays";
        String scope = spec.datasetId().toString();
        String reqHash = sha256(spec.datasetId() + ":" + spec.ruleProfileId() + ":" + spec.policyArtifactId()
                + ":" + spec.seed() + ":" + spec.datasetHash() + ":" + spec.ruleHash() + ":" + spec.policyHash()
                + ":" + spec.dataLayer() + ":" + Decimal.wire(spec.initialDeposit()));

        var existing = jdbc.query(
                "SELECT request_hash, result_ref FROM idempotency_records WHERE subject=? AND route=? AND scope=? AND key=?",
                (rs, n) -> Map.entry(rs.getString("request_hash"), rs.getObject("result_ref", UUID.class)),
                ownerId, route, scope, idempotencyKey
        ).stream().findFirst();

        if (existing.isPresent()) {
            if (!existing.get().getKey().equals(reqHash)) {
                throw new BusinessException(BusinessException.Code.IDEMPOTENCY_CONFLICT);
            }
            return existing.get().getValue();
        }

        UUID sessionId = createHistoricalReplaySession(
                ownerId,
                spec.initialDeposit(),
                spec.ruleProfileId(),
                spec.policyArtifactId(),
                spec.startTime()
        );

        UUID replayId = UUID.randomUUID();
        FidelityReport initialFidelity = FidelityReport.classify(
                spec.dataLayer(),
                false,
                "TRADE_ONLY".equals(spec.dataLayer()),
                0,
                false
        );

        jdbc.update("""
                INSERT INTO replay_jobs (
                    id, owner_id, status, session_id, dataset_id, rule_profile_id, policy_artifact_id,
                    seed, dataset_hash, rule_hash, policy_hash, tie_policy, data_layer, fidelity,
                    ambiguous_bar_count, cancelled, created_at, updated_at
                )
                VALUES (?, ?, 'QUEUED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, false, ?, ?)
                """,
                replayId,
                ownerId,
                sessionId,
                spec.datasetId(),
                spec.ruleProfileId(),
                spec.policyArtifactId(),
                spec.seed(),
                spec.datasetHash(),
                spec.ruleHash(),
                spec.policyHash(),
                spec.tiePolicy(),
                spec.dataLayer(),
                initialFidelity.fidelity(),
                Timestamp.from(spec.startTime()),
                Timestamp.from(spec.startTime())
        );

        jdbc.update("""
                INSERT INTO idempotency_records (subject, route, scope, key, request_hash, result_ref)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                ownerId, route, scope, idempotencyKey, reqHash, replayId
        );

        return replayId;
    }

    @Transactional
    public ReplayResultView executeReplay(
            UUID ownerId,
            UUID replayId,
            List<ReplayInput> inputs,
            boolean recordedJournal,
            boolean hasGaps,
            boolean missingMark,
            boolean lateFunding,
            BooleanSupplier externalCancelSignal
    ) {
        record JobRow(
                UUID sessionId,
                UUID policyArtifactId,
                long seed,
                String datasetHash,
                String ruleHash,
                String policyHash,
                String dataLayer,
                boolean cancelled,
                Instant createdAt
        ) {
        }

        var jobOpt = jdbc.query("""
                SELECT session_id, policy_artifact_id, seed, dataset_hash, rule_hash, policy_hash,
                       data_layer, cancelled, created_at
                FROM replay_jobs
                WHERE id = ? AND owner_id = ?
                FOR UPDATE
                """,
                (rs, n) -> new JobRow(
                        rs.getObject("session_id", UUID.class),
                        rs.getObject("policy_artifact_id", UUID.class),
                        rs.getLong("seed"),
                        rs.getString("dataset_hash"),
                        rs.getString("rule_hash"),
                        rs.getString("policy_hash"),
                        rs.getString("data_layer"),
                        rs.getBoolean("cancelled"),
                        rs.getTimestamp("created_at").toInstant()
                ),
                replayId, ownerId
        ).stream().findFirst();

        if (jobOpt.isEmpty()) {
            throw new BusinessException(BusinessException.Code.NOT_FOUND);
        }
        JobRow job = jobOpt.get();

        String sessionMode = jdbc.queryForObject(
                "SELECT mode FROM simulation_sessions WHERE id = ?",
                String.class,
                job.sessionId()
        );
        ReplayScope scope = new ReplayScope(replayId, job.sessionId(), sessionMode, job.policyArtifactId());

        UUID availableId = jdbc.queryForObject(
                "SELECT id FROM wallet_accounts WHERE session_id = ? AND account_key = 'AVAILABLE'",
                UUID.class,
                job.sessionId()
        );
        UUID isolatedId = ensureReplayWalletAccount(job.sessionId(), "ISOLATED:REPLAY", "ISOLATED");
        UUID feesId = ensureReplayWalletAccount(job.sessionId(), "SIM_FEES", "SYSTEM");
        UUID pnlPoolId = ensureReplayWalletAccount(job.sessionId(), "SIM_PNL_POOL", "SYSTEM");
        UUID insuranceId = ensureReplayWalletAccount(job.sessionId(), "SIM_INSURANCE", "SYSTEM");

        var virtualClock = new VirtualClock(job.createdAt());

        final BigDecimal[] grossPnl = {BigDecimal.ZERO};
        final BigDecimal[] totalFees = {BigDecimal.ZERO};
        final BigDecimal[] signedFunding = {BigDecimal.ZERO};
        final BigDecimal[] liquidationFees = {BigDecimal.ZERO};
        final BigDecimal[] insuranceAdj = {BigDecimal.ZERO};
        final long[] ambiguousBars = {0L};
        final Instant[] lastSignalAvailableAt = {null};

        // Aktif açık pozisyon durumu (replay scope içinde)
        final PositionSide[] openSide = {null};
        final BigDecimal[] openQty = {BigDecimal.ZERO};
        final BigDecimal[] openEntry = {BigDecimal.ZERO};
        final BigDecimal[] openMargin = {BigDecimal.ZERO};
        final BigDecimal[] openStop = {null};
        final BigDecimal[] openTarget = {null};
        final long[] stepSeq = {0L};

        ReplayRunner.Engine engine = (input, clock, random) -> {
            stepSeq[0]++;
            try {
                JsonNode node = (input.payload() == null || input.payload().isBlank())
                        ? json.createObjectNode()
                        : json.readTree(input.payload());

                if (node.hasNonNull("targetSessionId")) {
                    UUID targetSession = UUID.fromString(node.get("targetSessionId").asText());
                    scope.verify(targetSession, replayId);
                } else {
                    scope.verify(job.sessionId(), replayId);
                }

                switch (input.kind()) {
                    case CANDLE_CLOSE -> {
                        // Mum kapanışı yalnız bu andan (clock.instant()) sonraki kararlar için kullanılabilir.
                        lastSignalAvailableAt[0] = clock.instant();
                    }
                    case ENTRY_FILL -> {
                        if (lastSignalAvailableAt[0] != null && node.hasNonNull("signalTime")) {
                            Instant signalTime = Instant.parse(node.get("signalTime").asText());
                            BarAmbiguity.verifyNoLookahead(lastSignalAvailableAt[0], signalTime, clock.instant());
                        }
                        if (openSide[0] == null && node.hasNonNull("marginBudget") && node.hasNonNull("entryPrice")) {
                            PositionSide side = PositionSide.valueOf(node.path("side").asText("LONG"));
                            BigDecimal marginBudget = Decimal.parse(node.get("marginBudget").asText());
                            BigDecimal leverage = Decimal.parse(node.path("leverage").asText("10"));
                            BigDecimal entryPrice = Decimal.parse(node.get("entryPrice").asText());
                            BigDecimal step = Decimal.parse(node.path("step").asText("0.001"));
                            BigDecimal feeRate = Decimal.parse(node.path("feeRate").asText("0.0005"));

                            BigDecimal currentAvail = accountBalance(availableId);
                            var openCalc = Finance.open(marginBudget, leverage, entryPrice, step, feeRate, currentAvail);
                            Journal openJournal = new Journal(
                                    UUID.randomUUID(),
                                    job.sessionId(),
                                    "replay:" + replayId + ":open:" + stepSeq[0],
                                    "seq:" + stepSeq[0],
                                    "OPEN_FILL",
                                    FillAccounting.opening(availableId, isolatedId, feesId, openCalc)
                            );
                            ledger.post(openJournal, clock.instant(), clock.instant());

                            openSide[0] = side;
                            openQty[0] = openCalc.quantity();
                            openEntry[0] = entryPrice;
                            openMargin[0] = openCalc.margin();
                            openStop[0] = node.hasNonNull("stopLoss") ? Decimal.parse(node.get("stopLoss").asText()) : null;
                            openTarget[0] = node.hasNonNull("takeProfit") ? Decimal.parse(node.get("takeProfit").asText()) : null;
                            totalFees[0] = totalFees[0].add(openCalc.fee());
                        }
                    }
                    case FUNDING_SETTLEMENT -> {
                        if (openSide[0] != null && node.hasNonNull("markPrice") && node.hasNonNull("fundingRate")) {
                            BigDecimal markPrice = Decimal.parse(node.get("markPrice").asText());
                            BigDecimal rate = Decimal.parse(node.get("fundingRate").asText());
                            BigDecimal availBefore = accountBalance(availableId);
                            var settlement = Finance.funding(openSide[0], openQty[0], markPrice, rate, availBefore, openMargin[0]);
                            BigDecimal deltaAvail = settlement.availableAfter().subtract(availBefore);
                            BigDecimal deltaIso = settlement.isolatedAfter().subtract(openMargin[0]);

                            if (settlement.signedFunding().signum() != 0) {
                                List<Journal.Posting> posts = new ArrayList<>();
                                if (deltaAvail.signum() != 0) {
                                    posts.add(new Journal.Posting(availableId, deltaAvail));
                                }
                                if (deltaIso.signum() != 0) {
                                    posts.add(new Journal.Posting(isolatedId, deltaIso));
                                }
                                posts.add(new Journal.Posting(pnlPoolId, settlement.signedFunding().negate()));
                                Journal fundingJournal = new Journal(
                                        UUID.randomUUID(),
                                        job.sessionId(),
                                        "replay:" + replayId + ":funding:" + stepSeq[0],
                                        "seq:" + stepSeq[0],
                                        "FUNDING_SETTLEMENT",
                                        posts
                                );
                                ledger.post(fundingJournal, clock.instant(), clock.instant());
                                openMargin[0] = settlement.isolatedAfter();
                                signedFunding[0] = signedFunding[0].add(settlement.signedFunding());
                            }
                        }
                    }
                    case BRACKET -> {
                        if (openSide[0] != null && node.hasNonNull("markLow") && node.hasNonNull("markHigh")) {
                            BigDecimal low = Decimal.parse(node.get("markLow").asText());
                            BigDecimal high = Decimal.parse(node.get("markHigh").asText());
                            var trigger = BarAmbiguity.bracket(openSide[0], low, high, openStop[0], openTarget[0]);
                            if (trigger.ambiguous()) {
                                ambiguousBars[0]++;
                            }
                            if (!"NONE".equals(trigger.reason())) {
                                BigDecimal exitPrice = "STOP_LOSS".equals(trigger.reason()) ? openStop[0] : openTarget[0];
                                BigDecimal feeRate = Decimal.parse(node.path("feeRate").asText("0.0005"));
                                var closeCalc = Finance.close(
                                        openSide[0], openQty[0], openQty[0], openEntry[0], exitPrice, openMargin[0], feeRate
                                );
                                Journal closeJournal = new Journal(
                                        UUID.randomUUID(),
                                        job.sessionId(),
                                        "replay:" + replayId + ":bracket:" + stepSeq[0],
                                        "seq:" + stepSeq[0],
                                        trigger.reason(),
                                        FillAccounting.closing(availableId, isolatedId, pnlPoolId, feesId, insuranceId, closeCalc)
                                );
                                ledger.post(closeJournal, clock.instant(), clock.instant());
                                grossPnl[0] = grossPnl[0].add(closeCalc.realized());
                                totalFees[0] = totalFees[0].add(closeCalc.fee());
                                insuranceAdj[0] = insuranceAdj[0].add(closeCalc.insurance());
                                openSide[0] = null;
                                openQty[0] = BigDecimal.ZERO;
                                openMargin[0] = BigDecimal.ZERO;
                            }
                        }
                    }
                    case QUOTE_EXIT -> {
                        if (openSide[0] != null && node.hasNonNull("exitPrice")) {
                            BigDecimal exitPrice = Decimal.parse(node.get("exitPrice").asText());
                            BigDecimal feeRate = Decimal.parse(node.path("feeRate").asText("0.0005"));
                            String reason = node.path("reason").asText("CLOSE_FILL");
                            var closeCalc = Finance.close(
                                    openSide[0], openQty[0], openQty[0], openEntry[0], exitPrice, openMargin[0], feeRate
                            );
                            Journal closeJournal = new Journal(
                                    UUID.randomUUID(),
                                    job.sessionId(),
                                    "replay:" + replayId + ":exit:" + stepSeq[0],
                                    "seq:" + stepSeq[0],
                                    reason,
                                    FillAccounting.closing(availableId, isolatedId, pnlPoolId, feesId, insuranceId, closeCalc)
                            );
                            ledger.post(closeJournal, clock.instant(), clock.instant());
                            grossPnl[0] = grossPnl[0].add(closeCalc.realized());
                            totalFees[0] = totalFees[0].add(closeCalc.fee());
                            if ("LIQUIDATION".equals(reason)) {
                                liquidationFees[0] = liquidationFees[0].add(closeCalc.fee());
                            }
                            insuranceAdj[0] = insuranceAdj[0].add(closeCalc.insurance());
                            openSide[0] = null;
                            openQty[0] = BigDecimal.ZERO;
                            openMargin[0] = BigDecimal.ZERO;
                        }
                    }
                    default -> {
                        // STATUS_BOUNDARY veya MARK adımı
                    }
                }
            } catch (IllegalArgumentException | IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("REPLAY_STEP_FAILED", e);
            }
        };

        BooleanSupplier combinedCancel = () ->
                job.cancelled()
                        || (externalCancelSignal != null && externalCancelSignal.getAsBoolean())
                        || Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT cancelled FROM replay_jobs WHERE id = ?", Boolean.class, replayId));

        FidelityReport initialReport = FidelityReport.classify(
                job.dataLayer(), hasGaps, missingMark, 0, lateFunding
        );

        ReplayRunner runner = new ReplayRunner();
        ReplayRunner.Result runResult = runner.run(
                inputs,
                recordedJournal,
                job.seed(),
                virtualClock,
                engine,
                combinedCancel,
                initialReport.fidelity()
        );

        FidelityReport finalReport = FidelityReport.classify(
                job.dataLayer(),
                hasGaps,
                missingMark,
                ambiguousBars[0],
                lateFunding
        );

        if ("CANCELLED".equals(runResult.status())) {
            // Cancelled run'ın kısmi sonucu SUCCEEDED olarak sunulmaz; result_artifact_id = NULL kalır.
            jdbc.update("""
                    UPDATE replay_jobs
                    SET status = 'CANCELLED',
                        cancelled = true,
                        fidelity = ?,
                        ambiguous_bar_count = ?,
                        result_artifact_id = NULL,
                        updated_at = ?
                    WHERE id = ?
                    """,
                    finalReport.fidelity(),
                    finalReport.ambiguousBarCount(),
                    Timestamp.from(virtualClock.instant()),
                    replayId
            );
            return new ReplayResultView(
                    replayId,
                    ownerId,
                    job.sessionId(),
                    "CANCELLED",
                    job.dataLayer(),
                    finalReport.fidelity(),
                    finalReport.ambiguousBarCount(),
                    job.datasetHash(),
                    job.ruleHash(),
                    job.policyHash(),
                    job.seed(),
                    null,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    accountBalance(availableId),
                    null,
                    null
            );
        }

        String finalStatus = "INDETERMINATE".equals(finalReport.fidelity()) ? "INDETERMINATE" : "SUCCEEDED";
        BigDecimal finalAvail = accountBalance(availableId);
        BigDecimal netPnl = grossPnl[0].subtract(totalFees[0]).add(signedFunding[0]).subtract(liquidationFees[0]);

        String economicChecksum = computeEconomicChecksum(
                job.sessionId(),
                job.datasetHash(),
                job.ruleHash(),
                job.policyHash(),
                job.seed(),
                grossPnl[0],
                netPnl,
                totalFees[0],
                signedFunding[0],
                liquidationFees[0],
                insuranceAdj[0],
                finalAvail,
                finalReport
        );

        String csvPayload = "replayId,status,dataLayer,fidelity,ambiguousBarCount,grossPnl,netPnl,totalFees,signedFunding,liquidationFees,insuranceAdjustment,finalAvailable,economicChecksum\n"
                + replayId + ","
                + finalStatus + ","
                + finalReport.dataLayer() + ","
                + finalReport.fidelity() + ","
                + finalReport.ambiguousBarCount() + ","
                + Decimal.wire(Decimal.cash(grossPnl[0])) + ","
                + Decimal.wire(Decimal.cash(netPnl)) + ","
                + Decimal.wire(Decimal.cash(totalFees[0])) + ","
                + Decimal.wire(Decimal.cash(signedFunding[0])) + ","
                + Decimal.wire(Decimal.cash(liquidationFees[0])) + ","
                + Decimal.wire(Decimal.cash(insuranceAdj[0])) + ","
                + Decimal.wire(Decimal.cash(finalAvail)) + ","
                + economicChecksum + "\n";

        UUID artifactId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO replay_results (
                    artifact_id, replay_id, owner_id, session_id,
                    gross_pnl, net_pnl, total_fees, signed_funding, liquidation_fees,
                    insurance_adjustment, final_available, economic_checksum, csv_payload, created_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                artifactId,
                replayId,
                ownerId,
                job.sessionId(),
                Decimal.cash(grossPnl[0]),
                Decimal.cash(netPnl),
                Decimal.cash(totalFees[0]),
                Decimal.cash(signedFunding[0]),
                Decimal.cash(liquidationFees[0]),
                Decimal.cash(insuranceAdj[0]),
                Decimal.cash(finalAvail),
                economicChecksum,
                csvPayload,
                Timestamp.from(virtualClock.instant())
        );

        jdbc.update("""
                UPDATE replay_jobs
                SET status = ?,
                    fidelity = ?,
                    ambiguous_bar_count = ?,
                    result_artifact_id = ?,
                    updated_at = ?
                WHERE id = ?
                """,
                finalStatus,
                finalReport.fidelity(),
                finalReport.ambiguousBarCount(),
                artifactId,
                Timestamp.from(virtualClock.instant()),
                replayId
        );

        jdbc.update(
                "UPDATE simulation_sessions SET status = 'COMPLETED' WHERE id = ?",
                job.sessionId()
        );

        return findReplayView(ownerId, replayId).orElseThrow();
    }

    @Transactional
    public boolean cancelReplay(UUID ownerId, UUID replayId, Instant now) {
        int changed = jdbc.update("""
                UPDATE replay_jobs
                SET cancelled = true,
                    status = CASE WHEN status IN ('QUEUED', 'RUNNING') THEN 'CANCELLED' ELSE status END,
                    result_artifact_id = CASE WHEN status IN ('QUEUED', 'RUNNING') THEN NULL ELSE result_artifact_id END,
                    updated_at = ?
                WHERE id = ? AND owner_id = ?
                """,
                Timestamp.from(now),
                replayId,
                ownerId
        );
        return changed == 1;
    }

    @Transactional(readOnly = true)
    public Optional<ReplayResultView> findReplayView(UUID ownerId, UUID replayId) {
        return jdbc.query("""
                SELECT j.id,
                       j.owner_id,
                       j.session_id,
                       j.status,
                       j.data_layer,
                       j.fidelity,
                       j.ambiguous_bar_count,
                       j.dataset_hash,
                       j.rule_hash,
                       j.policy_hash,
                       j.seed,
                       j.result_artifact_id,
                       r.gross_pnl,
                       r.net_pnl,
                       r.total_fees,
                       r.signed_funding,
                       r.liquidation_fees,
                       r.insurance_adjustment,
                       r.final_available,
                       r.economic_checksum,
                       r.csv_payload
                FROM replay_jobs j
                LEFT JOIN replay_results r ON r.replay_id = j.id
                WHERE j.id = ? AND j.owner_id = ?
                """,
                (rs, n) -> new ReplayResultView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("owner_id", UUID.class),
                        rs.getObject("session_id", UUID.class),
                        rs.getString("status"),
                        rs.getString("data_layer"),
                        rs.getString("fidelity"),
                        rs.getLong("ambiguous_bar_count"),
                        rs.getString("dataset_hash"),
                        rs.getString("rule_hash"),
                        rs.getString("policy_hash"),
                        rs.getLong("seed"),
                        rs.getObject("result_artifact_id", UUID.class),
                        rs.getBigDecimal("gross_pnl"),
                        rs.getBigDecimal("net_pnl"),
                        rs.getBigDecimal("total_fees"),
                        rs.getBigDecimal("signed_funding"),
                        rs.getBigDecimal("liquidation_fees"),
                        rs.getBigDecimal("insurance_adjustment"),
                        rs.getBigDecimal("final_available"),
                        rs.getString("economic_checksum"),
                        rs.getString("csv_payload")
                ),
                replayId,
                ownerId
        ).stream().findFirst();
    }

    @Transactional(readOnly = true)
    public String exportCsv(UUID ownerId, UUID replayId) {
        var view = findReplayView(ownerId, replayId)
                .orElseThrow(() -> new BusinessException(BusinessException.Code.NOT_FOUND));
        if (view.csvPayload() == null) {
            throw new IllegalStateException("REPLAY_RESULT_NOT_AVAILABLE");
        }
        return view.csvPayload();
    }

    public List<ReplayInput> readJsonlInputs(Path verifiedJsonlFile) throws Exception {
        List<ReplayInput> inputs = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(verifiedJsonlFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node = json.readTree(line);
                inputs.add(new ReplayInput(
                        UUID.fromString(node.get("id").asText()),
                        Instant.parse(node.get("time").asText()),
                        ReplayInput.Kind.valueOf(node.get("kind").asText()),
                        node.get("originalSequence").asLong(),
                        node.get("payload").isTextual()
                                ? node.get("payload").asText()
                                : json.writeValueAsString(node.get("payload"))
                ));
            }
        }
        return inputs;
    }

    private UUID createHistoricalReplaySession(
            UUID ownerId,
            BigDecimal deposit,
            UUID ruleProfileId,
            UUID riskPolicyId,
            Instant startTime
    ) {
        BigDecimal cash = Decimal.cash(Decimal.positive(deposit));
        UUID sessionId = UUID.randomUUID();
        UUID availableId = UUID.randomUUID();
        UUID capitalId = UUID.randomUUID();

        jdbc.update("""
                INSERT INTO simulation_sessions (
                    id, owner_id, mode, initial_deposit, rule_profile_id, risk_policy_id, status, created_at
                )
                VALUES (?, ?, 'HISTORICAL_REPLAY', ?, ?, ?, 'RUNNING', ?)
                """,
                sessionId,
                ownerId,
                cash,
                ruleProfileId,
                riskPolicyId,
                Timestamp.from(startTime)
        );

        jdbc.update("""
                INSERT INTO wallet_accounts (id, session_id, account_key, kind)
                VALUES (?, ?, 'AVAILABLE', 'AVAILABLE'),
                       (?, ?, 'INITIAL_CAPITAL', 'SYSTEM')
                """,
                availableId, sessionId,
                capitalId, sessionId
        );

        Journal initialCapital = new Journal(
                UUID.randomUUID(),
                sessionId,
                "initial:" + sessionId,
                "deposit:" + cash.toPlainString(),
                "INITIAL_CAPITAL",
                List.of(
                        new Journal.Posting(availableId, cash),
                        new Journal.Posting(capitalId, cash.negate())
                )
        );
        ledger.post(initialCapital, startTime, startTime);
        return sessionId;
    }

    private UUID ensureReplayWalletAccount(UUID sessionId, String accountKey, String kind) {
        var existing = jdbc.queryForList(
                "SELECT id FROM wallet_accounts WHERE session_id = ? AND account_key = ?",
                UUID.class,
                sessionId,
                accountKey
        );
        if (!existing.isEmpty()) {
            return existing.getFirst();
        }
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO wallet_accounts(id, session_id, account_key, kind) VALUES (?, ?, ?, ?)",
                id, sessionId, accountKey, kind
        );
        return id;
    }

    private BigDecimal accountBalance(UUID accountId) {
        return jdbc.queryForObject(
                "SELECT balance FROM wallet_accounts WHERE id = ?",
                BigDecimal.class,
                accountId
        );
    }

    private String computeEconomicChecksum(
            UUID sessionId,
            String datasetHash,
            String ruleHash,
            String policyHash,
            long seed,
            BigDecimal grossPnl,
            BigDecimal netPnl,
            BigDecimal totalFees,
            BigDecimal signedFunding,
            BigDecimal liquidationFees,
            BigDecimal insuranceAdj,
            BigDecimal finalAvail,
            FidelityReport report
    ) {
        // UUID ve recorded wall-clock gibi deterministik olmayan alanlar ekonomik checksum'a DAHİL EDİLMEZ.
        // Yalnız ekonomik sonuçlar, sanal zamanlı (effective_at) hareket nedenleri ve hesap bakiyeleri dahil edilir.
        List<String> journalReasons = jdbc.query(
                "SELECT reason, effective_at FROM journal_transactions WHERE session_id = ? ORDER BY effective_at ASC, reason ASC",
                (rs, n) -> rs.getString("reason") + "@" + rs.getTimestamp("effective_at").toInstant(),
                sessionId
        );

        String canonical = String.join("|",
                datasetHash,
                ruleHash,
                policyHash,
                Long.toString(seed),
                report.dataLayer(),
                report.fidelity(),
                Long.toString(report.ambiguousBarCount()),
                Decimal.wire(Decimal.cash(grossPnl)),
                Decimal.wire(Decimal.cash(netPnl)),
                Decimal.wire(Decimal.cash(totalFees)),
                Decimal.wire(Decimal.cash(signedFunding)),
                Decimal.wire(Decimal.cash(liquidationFees)),
                Decimal.wire(Decimal.cash(insuranceAdj)),
                Decimal.wire(Decimal.cash(finalAvail)),
                String.join(",", journalReasons)
        );
        return sha256(canonical);
    }

    private static String sha256(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HASH_ERROR", e);
        }
    }
}

