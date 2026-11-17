package com.crypto.simulationservice.application.service;

import com.crypto.financecore.finance.Decimal;
import com.crypto.financecore.finance.Finance;
import com.crypto.financecore.finance.MaintenanceTier;
import com.crypto.financecore.finance.PositionSide;
import com.crypto.simulationservice.application.service.SimulationMarketCatalog.HistoricalCandle;
import com.crypto.simulationservice.application.service.SimulationMarketCatalog.HistoricalCandleProvider;
import com.crypto.simulationservice.domain.exception.BusinessException;
import com.crypto.simulationservice.domain.service.RiskDecision;
import com.crypto.simulationservice.domain.service.replay.BarAmbiguity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

@Service
public class SimulationReportingService {

    public static final List<MaintenanceTier> DEFAULT_MAINTENANCE_TIERS = List.of(
            new MaintenanceTier(
                    BigDecimal.ZERO,
                    new BigDecimal("100000000"),
                    new BigDecimal("0.005"),
                    BigDecimal.ZERO
            )
    );
    public static final BigDecimal DEFAULT_LIQUIDATION_FEE_RATE = new BigDecimal("0.005");

    public enum AutoExitReason {
        LIQUIDATED, STOP_LOSS, TAKE_PROFIT
    }

    public record PositionExitEvent(
            UUID sessionId,
            UUID positionId,
            UUID instrumentId,
            String symbol,
            String side,
            AutoExitReason reason,
            BigDecimal markPrice,
            BigDecimal closingFee,
            BigDecimal insurance,
            BigDecimal realizedPnl,
            Instant closedAt
    ) {}

    public record LiquidatedPositionEvent(
            UUID sessionId,
            UUID positionId,
            UUID instrumentId,
            String symbol,
            String side,
            BigDecimal markPrice,
            BigDecimal liquidationFee,
            BigDecimal insurance,
            Instant closedAt
    ) {}

    private record SettlementOutcome(BigDecimal closingFee, BigDecimal insurance, BigDecimal realizedPnl) {}

    public record SessionSnapshot(
            UUID sessionId,
            UUID ownerId,
            String mode,
            String currency,
            long engineSequence,
            String equity,
            String unrealizedPnl,
            Instant asOf
    ) {}

    public record TradeSnapshot(
            UUID positionId,
            UUID sessionId,
            UUID instrumentId,
            String symbol,
            String side,
            String status,
            Instant openedAt,
            Instant closedAt,
            String realizedPnl,
            String totalFees,
            String signedFunding,
            String liquidationFees,
            String insuranceAdjustment
    ) {}

    public record ReportingSnapshot(
            UUID ownerId,
            List<SessionSnapshot> sessions,
            List<UUID> deletedSessionIds,
            List<TradeSnapshot> trades
    ) {}

    private record OpenPositionRow(
            UUID positionId,
            UUID sessionId,
            UUID instrumentId,
            String side,
            BigDecimal q,
            BigDecimal entryPrice,
            BigDecimal margin,
            BigDecimal stopLoss,
            BigDecimal takeProfit,
            UUID ruleProfileId,
            BigDecimal openFee,
            BigDecimal settledFunding,
            Instant openedAt,
            Instant deadlineAt,
            String expiryPolicy
    ) {}

    private record PlannedExit(
            RiskDecision.Action action,
            BigDecimal exitPrice,
            Instant exitTime
    ) {}

    private record SessionRow(
            UUID sessionId,
            UUID ownerId,
            String mode,
            BigDecimal initialDeposit,
            long engineSequence,
            boolean deleted,
            BigDecimal availableBalance,
            BigDecimal reservedBalance
    ) {}

    private final JdbcTemplate jdbc;
    private final SimulationMarketCatalog catalog;
    private final Clock clock;
    private final boolean historicalCatchUpEnabled;
    private volatile boolean tablesEnsured = false;

    public SimulationReportingService(JdbcTemplate jdbc, SimulationMarketCatalog catalog, Clock clock) {
        this(jdbc, catalog, clock, true);
    }

    @Autowired
    public SimulationReportingService(
            JdbcTemplate jdbc,
            SimulationMarketCatalog catalog,
            Clock clock,
            @Value("${simulation.liquidation.enabled:true}") boolean historicalCatchUpEnabled
    ) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.clock = clock;
        this.historicalCatchUpEnabled = historicalCatchUpEnabled;
    }

    public void ensureTables() {
        if (tablesEnsured) {
            return;
        }
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS session_metadata (
                    session_id uuid PRIMARY KEY REFERENCES simulation_sessions(id),
                    name text NOT NULL DEFAULT '',
                    deleted boolean NOT NULL DEFAULT false,
                    updated_at timestamptz NOT NULL DEFAULT now()
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS position_trade_outcomes (
                    position_id uuid PRIMARY KEY REFERENCES positions(id),
                    close_price numeric(38,18),
                    open_fee numeric(38,18) NOT NULL DEFAULT 0,
                    close_fee numeric(38,18) NOT NULL DEFAULT 0,
                    gross_pnl numeric(38,18) NOT NULL DEFAULT 0,
                    funding numeric(38,18) NOT NULL DEFAULT 0,
                    liquidation_fee numeric(38,18) NOT NULL DEFAULT 0,
                    insurance numeric(38,18) NOT NULL DEFAULT 0,
                    updated_at timestamptz NOT NULL DEFAULT now()
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS position_monitor_state (
                    position_id uuid PRIMARY KEY REFERENCES positions(id),
                    last_evaluated_at timestamptz NOT NULL,
                    updated_at timestamptz NOT NULL DEFAULT now()
                )
                """);
        catalog.ensureSymbolTable();
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            tablesEnsured = true;
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int closeOpenPositions(UUID sessionId, UUID instrumentIdFilter, UUID positionIdFilter, Instant now) {
        ensureTables();
        List<OpenPositionRow> openPositions = fetchOpenPositions(null, sessionId, instrumentIdFilter, positionIdFilter, true);

        int closedCount = 0;
        for (OpenPositionRow pos : openPositions) {
            BigDecimal markPrice = catalog.fetchLiveMarkPrice(pos.instrumentId(), pos.entryPrice());
            BigDecimal feeRate = SimulationMarketCatalog.resolveTakerFeeRate(pos.ruleProfileId(), "MARKET");
            if (settlePosition(pos, false, markPrice, feeRate, now) != null) {
                closedCount++;
            }
        }
        return closedCount;
    }

    @Transactional
    public List<LiquidatedPositionEvent> liquidateBreachedPositions(
            UUID ownerId,
            UUID sessionIdFilter,
            Instant now
    ) {
        return liquidateBreachedPositions(ownerId, sessionIdFilter, catalog::fetchLiveMarkPriceIfAvailable, now);
    }

    @Transactional
    public List<LiquidatedPositionEvent> liquidateBreachedPositions(
            UUID ownerId,
            UUID sessionIdFilter,
            Function<UUID, Optional<BigDecimal>> markPriceLookup,
            Instant now
    ) {
        return processAutomaticExits(ownerId, sessionIdFilter, markPriceLookup, now).stream()
                .filter(ev -> ev.reason() == AutoExitReason.LIQUIDATED)
                .map(ev -> new LiquidatedPositionEvent(
                        ev.sessionId(),
                        ev.positionId(),
                        ev.instrumentId(),
                        ev.symbol(),
                        ev.side(),
                        ev.markPrice(),
                        ev.closingFee(),
                        ev.insurance(),
                        ev.closedAt()
                ))
                .toList();
    }

    @Transactional
    public List<PositionExitEvent> processAutomaticExits(
            UUID ownerId,
            UUID sessionIdFilter,
            Instant now
    ) {
        return processAutomaticExits(
                ownerId,
                sessionIdFilter,
                catalog::fetchLiveMarkPriceIfAvailable,
                historicalCatchUpEnabled ? catalog::fetchHistoricalCandles : null,
                now
        );
    }

    @Transactional
    public List<PositionExitEvent> processAutomaticExits(
            UUID ownerId,
            UUID sessionIdFilter,
            Function<UUID, Optional<BigDecimal>> markPriceLookup,
            Instant now
    ) {
        return processAutomaticExits(ownerId, sessionIdFilter, markPriceLookup, null, now);
    }

    @Transactional
    public List<PositionExitEvent> processAutomaticExits(
            UUID ownerId,
            UUID sessionIdFilter,
            Function<UUID, Optional<BigDecimal>> markPriceLookup,
            @Nullable HistoricalCandleProvider historicalCandleProvider,
            Instant now
    ) {
        ensureTables();
        List<OpenPositionRow> candidates = fetchOpenPositions(ownerId, sessionIdFilter, null, null, false);
        if (candidates.isEmpty()) {
            return List.of();
        }

        Map<UUID, Instant> lastEvaluatedByPos = loadLastEvaluatedMap(candidates);
        Map<String, Instant> earliestSymbolCatchUpFrom = new HashMap<>();
        if (historicalCandleProvider != null) {
            for (OpenPositionRow candidate : candidates) {
                Instant cursorFrom = resolveEvaluationStart(candidate, lastEvaluatedByPos.get(candidate.positionId()), now);
                if (needsHistoricalCatchUp(candidate, cursorFrom, now)) {
                    Optional<String> symOpt = catalog.resolveKnownSymbol(candidate.instrumentId());
                    if (symOpt.isPresent()) {
                        String sym = symOpt.get();
                        earliestSymbolCatchUpFrom.merge(sym, cursorFrom, (a, b) -> a.isBefore(b) ? a : b);
                    }
                }
            }
        }

        Map<String, List<HistoricalCandle>> candlesBySymbol = new HashMap<>();
        for (Map.Entry<String, Instant> entry : earliestSymbolCatchUpFrom.entrySet()) {
            List<HistoricalCandle> bars = historicalCandleProvider.fetchCandles(
                    entry.getKey(), null, entry.getValue(), now
            );
            candlesBySymbol.put(entry.getKey(), bars != null ? bars : List.of());
        }

        Map<UUID, Optional<BigDecimal>> resolvedMarks = new HashMap<>();
        List<PositionExitEvent> exitEvents = new ArrayList<>();

        for (OpenPositionRow candidate : candidates) {
            if (candidate.q() == null || candidate.q().signum() <= 0
                    || candidate.entryPrice() == null || candidate.entryPrice().signum() <= 0) {
                continue;
            }
            Instant cursorFrom = resolveEvaluationStart(candidate, lastEvaluatedByPos.get(candidate.positionId()), now);
            Optional<String> knownSymbolOpt = catalog.resolveKnownSymbol(candidate.instrumentId());
            List<HistoricalCandle> symbolBars = knownSymbolOpt
                    .map(sym -> candlesBySymbol.getOrDefault(sym, List.of()))
                    .orElse(List.of());

            PlannedExit planned = evaluateCandidateExit(
                    candidate, cursorFrom, symbolBars, markPriceLookup, resolvedMarks, now
            );
            if (planned == null) {
                if (symbolBars.isEmpty()) {
                    maybeTightenProfitLockStopLoss(
                            candidate,
                            resolvedMarks.getOrDefault(candidate.instrumentId(), Optional.empty()).orElse(null)
                    );
                }
                advanceMonitorStateIfObserved(
                        candidate.positionId(),
                        symbolBars,
                        resolvedMarks.getOrDefault(candidate.instrumentId(), Optional.empty()).isPresent(),
                        now
                );
                continue;
            }

            List<OpenPositionRow> lockedList = fetchOpenPositions(
                    ownerId,
                    candidate.sessionId(),
                    null,
                    candidate.positionId(),
                    true
            );
            if (lockedList.isEmpty()) {
                continue;
            }
            OpenPositionRow locked = lockedList.getFirst();
            Instant lockedCursor = resolveEvaluationStart(locked, lastEvaluatedByPos.get(locked.positionId()), now);
            PlannedExit lockedPlanned = evaluateCandidateExit(
                    locked, lockedCursor, symbolBars, markPriceLookup, resolvedMarks, now
            );
            if (lockedPlanned == null) {
                advanceMonitorStateIfObserved(
                        locked.positionId(),
                        symbolBars,
                        resolvedMarks.getOrDefault(locked.instrumentId(), Optional.empty()).isPresent(),
                        now
                );
                continue;
            }

            boolean liquidated = lockedPlanned.action() == RiskDecision.Action.LIQUIDATE;
            BigDecimal feeRate = liquidated
                    ? DEFAULT_LIQUIDATION_FEE_RATE
                    : SimulationMarketCatalog.resolveTakerFeeRate(locked.ruleProfileId(), "MARKET");

            SettlementOutcome outcome = settlePosition(
                    locked, liquidated, lockedPlanned.exitPrice(), feeRate, lockedPlanned.exitTime()
            );
            if (outcome == null) {
                continue;
            }

            upsertMonitorState(locked.positionId(), lockedPlanned.exitTime());

            String cmdType = liquidated ? "LIQUIDATE_POSITION" : "CLOSE_POSITION";
            String cmdReason = liquidated ? "AUTO_LIQUIDATION" : lockedPlanned.action().name();
            UUID commandId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO commands(id, session_id, type, payload, status, reason, created_at, updated_at)
                    VALUES (?, ?, ?, '{}'::jsonb, 'FILLED', ?, ?, ?)
                    """, commandId, locked.sessionId(), cmdType, cmdReason,
                    Timestamp.from(lockedPlanned.exitTime()), Timestamp.from(lockedPlanned.exitTime()));

            AutoExitReason reason = liquidated
                    ? AutoExitReason.LIQUIDATED
                    : (lockedPlanned.action() == RiskDecision.Action.STOP_LOSS
                            ? AutoExitReason.STOP_LOSS
                            : AutoExitReason.TAKE_PROFIT);

            exitEvents.add(new PositionExitEvent(
                    locked.sessionId(),
                    locked.positionId(),
                    locked.instrumentId(),
                    catalog.resolveSymbol(locked.instrumentId()),
                    locked.side(),
                    reason,
                    lockedPlanned.exitPrice(),
                    outcome.closingFee(),
                    outcome.insurance(),
                    outcome.realizedPnl(),
                    lockedPlanned.exitTime()
            ));
        }

        return exitEvents;
    }

    private static Instant resolveEvaluationStart(OpenPositionRow pos, @Nullable Instant lastEvaluatedAt, Instant now) {
        Instant openedAt = pos.openedAt() != null ? pos.openedAt() : now;
        if (lastEvaluatedAt != null && lastEvaluatedAt.isAfter(openedAt)) {
            return lastEvaluatedAt;
        }
        return openedAt;
    }

    private static boolean needsHistoricalCatchUp(OpenPositionRow pos, Instant cursorFrom, Instant now) {
        if (!now.isAfter(cursorFrom)) {
            return false;
        }
        long offlineGapSeconds = Duration.between(cursorFrom, now).getSeconds();
        if (offlineGapSeconds > 90L) {
            return true;
        }
        Instant openedAt = pos.openedAt() != null ? pos.openedAt() : now;
        return pos.deadlineAt() != null
                && !now.isBefore(pos.deadlineAt())
                && Duration.between(openedAt, now).getSeconds() > 30L
                && !cursorFrom.isAfter(pos.deadlineAt());
    }

    private PlannedExit evaluateCandidateExit(
            OpenPositionRow pos,
            Instant cursorFrom,
            List<HistoricalCandle> symbolBars,
            Function<UUID, Optional<BigDecimal>> markPriceLookup,
            Map<UUID, Optional<BigDecimal>> resolvedMarks,
            Instant now
    ) {
        if (symbolBars != null && !symbolBars.isEmpty()) {
            PlannedExit historicalHit = evaluateHistoricalBars(pos, symbolBars, cursorFrom);
            if (historicalHit != null) {
                return historicalHit;
            }
        }

        Optional<BigDecimal> markOpt = resolvedMarks.computeIfAbsent(pos.instrumentId(), markPriceLookup);
        if (markOpt.isEmpty() || markOpt.get().signum() <= 0) {
            return null;
        }
        BigDecimal markPrice = markOpt.get();
        BigDecimal margin = pos.margin() != null ? pos.margin() : BigDecimal.ZERO;
        RiskDecision.Action action = RiskDecision.onMark(
                PositionSide.valueOf(pos.side()),
                pos.q(),
                pos.entryPrice(),
                markPrice,
                margin,
                pos.stopLoss(),
                pos.takeProfit(),
                DEFAULT_MAINTENANCE_TIERS
        );
        if (action == RiskDecision.Action.NONE
                && shouldTakeProfitAtDeadline(pos, markPrice, now)) {
            action = RiskDecision.Action.TAKE_PROFIT;
        }
        return action == RiskDecision.Action.NONE ? null : new PlannedExit(action, markPrice, now);
    }

    /**
     * Kâra geçmiş ve hem SL hem TP tanımlı açık pozisyonlarda:
     * - TP yolunun %55'ine ulaşıldığında stop_loss'u giriş + gidiş-dönüş komisyon maliyetinin üzerine (net kârlı başabaş),
     * - TP yolunun %78'ine ulaşıldığında TP mesafesinin %40'ını kilitleyen trailing stop seviyesine taşır.
     */
    private void maybeTightenProfitLockStopLoss(OpenPositionRow pos, @Nullable BigDecimal markPrice) {
        if (markPrice == null || markPrice.signum() <= 0
                || pos.entryPrice() == null || pos.entryPrice().signum() <= 0
                || pos.stopLoss() == null || pos.stopLoss().signum() <= 0
                || pos.takeProfit() == null || pos.takeProfit().signum() <= 0) {
            return;
        }
        boolean isLong = "LONG".equals(pos.side());
        BigDecimal entry = pos.entryPrice();
        BigDecimal tp = pos.takeProfit();
        BigDecimal currentSl = pos.stopLoss();

        BigDecimal targetDist = isLong ? tp.subtract(entry) : entry.subtract(tp);
        if (targetDist.signum() <= 0) {
            return;
        }
        BigDecimal favorableMove = isLong ? markPrice.subtract(entry) : entry.subtract(markPrice);
        if (favorableMove.signum() <= 0) {
            return;
        }

        BigDecimal progress = favorableMove.divide(targetDist, Decimal.MC);
        if (progress.compareTo(new BigDecimal("0.55")) < 0) {
            return;
        }

        BigDecimal closeFeeRate = SimulationMarketCatalog.resolveTakerFeeRate(pos.ruleProfileId(), "MARKET");
        BigDecimal notional = pos.q().multiply(entry, Decimal.MC);
        BigDecimal actualOpenRate = (pos.openFee() != null && pos.openFee().signum() > 0 && notional.signum() > 0)
                ? pos.openFee().divide(notional, Decimal.MC)
                : closeFeeRate;
        BigDecimal beOffsetRate = actualOpenRate.add(closeFeeRate).add(new BigDecimal("0.0003"))
                .max(new BigDecimal("0.0010"));

        BigDecimal breakEvenSl = isLong
                ? entry.multiply(BigDecimal.ONE.add(beOffsetRate))
                : entry.multiply(BigDecimal.ONE.subtract(beOffsetRate));

        BigDecimal candidateSl = breakEvenSl;
        if (progress.compareTo(new BigDecimal("0.78")) >= 0) {
            BigDecimal lockDist = targetDist.multiply(new BigDecimal("0.40"), Decimal.MC);
            BigDecimal trailSl = isLong ? entry.add(lockDist) : entry.subtract(lockDist);
            boolean trailStrictlyBetter = isLong
                    ? trailSl.compareTo(breakEvenSl) > 0
                    : trailSl.compareTo(breakEvenSl) < 0;
            if (trailStrictlyBetter) {
                candidateSl = trailSl;
            }
        }

        int scale = Math.min(8, Math.max(4, Math.max(entry.scale(), currentSl.scale())));
        BigDecimal roundedSl = candidateSl.setScale(scale, RoundingMode.HALF_UP);
        boolean improvesAndSafe = isLong
                ? (roundedSl.compareTo(currentSl) > 0
                        && roundedSl.compareTo(markPrice) < 0
                        && roundedSl.compareTo(tp) < 0)
                : (roundedSl.compareTo(currentSl) < 0
                        && roundedSl.compareTo(markPrice) > 0
                        && roundedSl.compareTo(tp) > 0);
        if (!improvesAndSafe) {
            return;
        }

        jdbc.update("""
                UPDATE positions
                SET stop_loss = ?, version = version + 1
                WHERE id = ? AND state IN ('OPEN', 'LIQUIDATING')
                  AND (
                        (side = 'LONG' AND stop_loss IS NOT NULL AND stop_loss < ?)
                     OR (side = 'SHORT' AND stop_loss IS NOT NULL AND stop_loss > ?)
                  )
                """, roundedSl, pos.positionId(), roundedSl, roundedSl);
    }

    private static PlannedExit evaluateHistoricalBars(
            OpenPositionRow pos,
            List<HistoricalCandle> bars,
            Instant cursorFrom
    ) {
        PositionSide side = PositionSide.valueOf(pos.side());
        boolean isLong = side == PositionSide.LONG;
        BigDecimal margin = pos.margin() != null ? pos.margin() : BigDecimal.ZERO;
        BigDecimal validStop = pos.stopLoss() != null && pos.stopLoss().signum() > 0 ? pos.stopLoss() : null;
        BigDecimal validTarget = pos.takeProfit() != null && pos.takeProfit().signum() > 0 ? pos.takeProfit() : null;
        Instant openedAt = pos.openedAt() != null ? pos.openedAt() : cursorFrom;

        for (HistoricalCandle bar : bars) {
            if (!bar.closed() || !bar.closeTime().isAfter(cursorFrom)) {
                continue;
            }
            if (bar.openTime().isBefore(openedAt)) {
                // Giriş mumunun pozisyon açılmadan önceki saniyelerindeki fitili taramayız; yalnızca mum kapanışını kontrol ederiz.
                RiskDecision.Action closeAction = RiskDecision.onMark(
                        side, pos.q(), pos.entryPrice(), bar.close(), margin, validStop, validTarget, DEFAULT_MAINTENANCE_TIERS
                );
                if (closeAction != RiskDecision.Action.NONE) {
                    return new PlannedExit(closeAction, bar.close(), bar.closeTime());
                }
                if (shouldTakeProfitAtDeadline(pos, bar.close(), bar.closeTime())) {
                    return new PlannedExit(RiskDecision.Action.TAKE_PROFIT, bar.close(), bar.closeTime());
                }
                continue;
            }

            BigDecimal adverse = isLong ? bar.low() : bar.high();
            if (Finance.liquidates(side, pos.q(), pos.entryPrice(), adverse, margin, DEFAULT_MAINTENANCE_TIERS)) {
                BigDecimal liqPrice = Finance.liquidation(
                        side, pos.q(), pos.entryPrice(), margin, DEFAULT_MAINTENANCE_TIERS
                ).orElse(adverse);
                BigDecimal exitPrice = isLong
                        ? (bar.open().compareTo(liqPrice) < 0 ? bar.open() : liqPrice)
                        : (bar.open().compareTo(liqPrice) > 0 ? bar.open() : liqPrice);
                exitPrice = exitPrice.max(bar.low()).min(bar.high());
                return new PlannedExit(RiskDecision.Action.LIQUIDATE, exitPrice, bar.closeTime());
            }

            BarAmbiguity.Trigger trigger = BarAmbiguity.bracket(side, bar.low(), bar.high(), validStop, validTarget);
            if ("STOP_LOSS".equals(trigger.reason()) && validStop != null) {
                BigDecimal fillPrice = isLong
                        ? (bar.open().compareTo(validStop) <= 0 ? bar.open() : validStop)
                        : (bar.open().compareTo(validStop) >= 0 ? bar.open() : validStop);
                fillPrice = fillPrice.max(bar.low()).min(bar.high());
                return new PlannedExit(RiskDecision.Action.STOP_LOSS, fillPrice, bar.closeTime());
            }
            if ("TAKE_PROFIT".equals(trigger.reason()) && validTarget != null) {
                BigDecimal fillPrice = isLong
                        ? (bar.open().compareTo(validTarget) >= 0 ? bar.open() : validTarget)
                        : (bar.open().compareTo(validTarget) <= 0 ? bar.open() : validTarget);
                fillPrice = fillPrice.max(bar.low()).min(bar.high());
                return new PlannedExit(RiskDecision.Action.TAKE_PROFIT, fillPrice, bar.closeTime());
            }

            if (shouldTakeProfitAtDeadline(pos, bar.close(), bar.closeTime())) {
                return new PlannedExit(RiskDecision.Action.TAKE_PROFIT, bar.close(), bar.closeTime());
            }
        }
        return null;
    }

    private Map<UUID, Instant> loadLastEvaluatedMap(List<OpenPositionRow> candidates) {
        Map<UUID, Instant> map = new HashMap<>();
        if (candidates.isEmpty()) {
            return map;
        }
        List<String> placeholders = new ArrayList<>(candidates.size());
        List<Object> params = new ArrayList<>(candidates.size());
        for (OpenPositionRow c : candidates) {
            placeholders.add("?");
            params.add(c.positionId());
        }
        String sql = "SELECT position_id, last_evaluated_at FROM position_monitor_state WHERE position_id IN ("
                + String.join(",", placeholders) + ")";
        jdbc.query(sql, rs -> {
            UUID pid = rs.getObject("position_id", UUID.class);
            Timestamp ts = rs.getTimestamp("last_evaluated_at");
            if (pid != null && ts != null) {
                map.put(pid, ts.toInstant());
            }
        }, params.toArray());
        return map;
    }

    private void advanceMonitorStateIfObserved(
            UUID positionId,
            List<HistoricalCandle> symbolBars,
            boolean liveMarkObserved,
            Instant now
    ) {
        Instant observedAt = null;
        if (liveMarkObserved) {
            observedAt = now;
        } else if (symbolBars != null && !symbolBars.isEmpty()) {
            for (int i = symbolBars.size() - 1; i >= 0; i--) {
                if (symbolBars.get(i).closed()) {
                    observedAt = symbolBars.get(i).closeTime();
                    break;
                }
            }
        }
        if (observedAt != null) {
            upsertMonitorState(positionId, observedAt);
        }
    }

    private void upsertMonitorState(UUID positionId, Instant observedAt) {
        if (positionId == null || observedAt == null) {
            return;
        }
        jdbc.update("""
                INSERT INTO position_monitor_state(position_id, last_evaluated_at, updated_at)
                VALUES (?, ?, ?)
                ON CONFLICT (position_id) DO UPDATE SET
                    last_evaluated_at = EXCLUDED.last_evaluated_at,
                    updated_at = EXCLUDED.updated_at
                """, positionId, Timestamp.from(observedAt), Timestamp.from(observedAt));
    }

    private List<OpenPositionRow> fetchOpenPositions(
            UUID ownerId,
            UUID sessionIdFilter,
            UUID instrumentIdFilter,
            UUID positionIdFilter,
            boolean forUpdate
    ) {
        var sql = new StringBuilder("""
                SELECT
                    p.id,
                    p.session_id,
                    p.instrument_id,
                    p.side,
                    p.q,
                    p.entry_price,
                    p.initial_margin_remaining,
                    p.stop_loss,
                    p.take_profit,
                    p.opened_at,
                    p.deadline_at,
                    p.expiry_policy,
                    s.rule_profile_id,
                    COALESCE((
                        SELECT (ep.canonical_json->>'openFeeEstimate')::numeric
                        FROM orders ord
                        JOIN commands cmd ON cmd.id = ord.command_id
                        JOIN execution_plans ep
                          ON cmd.type = 'SUBMIT'
                         AND cmd.payload->>'planId' IS NOT NULL
                         AND ep.plan_id = (cmd.payload->>'planId')::uuid
                         AND ep.revision = COALESCE(NULLIF(cmd.payload->>'revision', '')::int, 1)
                        WHERE ord.position_id = p.id
                        ORDER BY ord.created_at ASC
                        LIMIT 1
                    ), 0) AS open_fee,
                    COALESCE((
                        SELECT SUM(fs.amount)
                        FROM funding_settlements fs
                        WHERE fs.position_id = p.id
                    ), 0) AS settled_funding
                FROM positions p
                JOIN simulation_sessions s ON s.id = p.session_id
                WHERE p.state IN ('OPEN', 'LIQUIDATING')
                """);
        List<Object> params = new ArrayList<>();
        if (ownerId != null) {
            sql.append(" AND s.owner_id = ?");
            params.add(ownerId);
        }
        if (sessionIdFilter != null) {
            sql.append(" AND p.session_id = ?");
            params.add(sessionIdFilter);
        }
        if (instrumentIdFilter != null) {
            sql.append(" AND p.instrument_id = ?");
            params.add(instrumentIdFilter);
        }
        if (positionIdFilter != null) {
            sql.append(" AND p.id = ?");
            params.add(positionIdFilter);
        }
        if (forUpdate) {
            sql.append(" FOR UPDATE OF p");
        }

        return jdbc.query(
                sql.toString(),
                (rs, n) -> new OpenPositionRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("session_id", UUID.class),
                        rs.getObject("instrument_id", UUID.class),
                        rs.getString("side"),
                        rs.getBigDecimal("q"),
                        rs.getBigDecimal("entry_price"),
                        rs.getBigDecimal("initial_margin_remaining"),
                        rs.getBigDecimal("stop_loss"),
                        rs.getBigDecimal("take_profit"),
                        rs.getObject("rule_profile_id", UUID.class),
                        rs.getBigDecimal("open_fee"),
                        rs.getBigDecimal("settled_funding"),
                        rs.getTimestamp("opened_at") != null ? rs.getTimestamp("opened_at").toInstant() : null,
                        rs.getTimestamp("deadline_at") != null ? rs.getTimestamp("deadline_at").toInstant() : null,
                        rs.getString("expiry_policy")
                ),
                params.toArray()
        );
    }

    /**
     * Vade süresi dolmuş ve komisyonlar sonrası net kârda olan CLOSE_AT_DEADLINE pozisyonlarını
     * otomatik olarak kârla kapatıp kazancı kilitler; zarardaki pozisyonları zorla kapatmaz.
     */
    private static boolean shouldTakeProfitAtDeadline(
            OpenPositionRow pos,
            BigDecimal markPrice,
            Instant now
    ) {
        if (!"CLOSE_AT_DEADLINE".equals(pos.expiryPolicy())
                || pos.deadlineAt() == null
                || now.isBefore(pos.deadlineAt())) {
            return false;
        }
        BigDecimal margin = pos.margin() != null ? pos.margin() : BigDecimal.ZERO;
        BigDecimal feeRate = SimulationMarketCatalog.resolveTakerFeeRate(pos.ruleProfileId(), "MARKET");
        Finance.Closing closing = Finance.close(
                PositionSide.valueOf(pos.side()),
                pos.q(),
                pos.q(),
                pos.entryPrice(),
                markPrice,
                margin,
                feeRate
        );
        BigDecimal grossPnl = Decimal.cash(closing.realized());
        BigDecimal closingFee = Decimal.cash(closing.fee());
        BigDecimal openFee = pos.openFee() != null ? Decimal.cash(pos.openFee()) : BigDecimal.ZERO;
        BigDecimal settledFunding = pos.settledFunding() != null ? Decimal.cash(pos.settledFunding()) : BigDecimal.ZERO;
        BigDecimal netPnl = grossPnl.add(settledFunding).subtract(openFee).subtract(closingFee);
        return netPnl.compareTo(BigDecimal.ZERO) > 0;
    }

    private SettlementOutcome settlePosition(
            OpenPositionRow pos,
            boolean liquidated,
            BigDecimal exitPrice,
            BigDecimal feeRate,
            Instant now
    ) {
        String nextState = liquidated ? "LIQUIDATED" : "CLOSED";
        int updated = jdbc.update("""
                UPDATE positions
                SET state = ?, close_requested = true, closed_at = ?, version = version + 1
                WHERE id = ? AND state IN ('OPEN', 'LIQUIDATING')
                """, nextState, Timestamp.from(now), pos.positionId());
        if (updated == 0) {
            return null;
        }

        BigDecimal margin = pos.margin() != null ? pos.margin() : BigDecimal.ZERO;
        Finance.Closing closing = Finance.close(
                PositionSide.valueOf(pos.side()),
                pos.q(),
                pos.q(),
                pos.entryPrice(),
                exitPrice,
                margin,
                feeRate
        );
        BigDecimal grossPnl = Decimal.cash(closing.realized());
        BigDecimal closingFee = Decimal.cash(closing.fee());
        BigDecimal payout = Decimal.cash(closing.returnedCash());
        BigDecimal insurance = Decimal.cash(closing.insurance());

        if (payout.signum() > 0) {
            jdbc.update("""
                    UPDATE wallet_accounts
                    SET balance = balance + ?
                    WHERE session_id = ? AND kind = 'AVAILABLE'
                    """, payout, pos.sessionId());
        }

        jdbc.update("""
                UPDATE simulation_sessions
                SET engine_sequence = engine_sequence + 1
                WHERE id = ?
                """, pos.sessionId());

        BigDecimal openFee = pos.openFee() != null ? Decimal.cash(pos.openFee()) : BigDecimal.ZERO;
        BigDecimal settledFunding = pos.settledFunding() != null ? Decimal.cash(pos.settledFunding()) : BigDecimal.ZERO;
        BigDecimal closeFeeCol = liquidated ? BigDecimal.ZERO : closingFee;
        BigDecimal liqFeeCol = liquidated ? closingFee : BigDecimal.ZERO;

        jdbc.update("""
                INSERT INTO position_trade_outcomes(
                    position_id, close_price, open_fee, close_fee, gross_pnl,
                    funding, liquidation_fee, insurance, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (position_id) DO UPDATE SET
                    close_price = EXCLUDED.close_price,
                    open_fee = EXCLUDED.open_fee,
                    close_fee = EXCLUDED.close_fee,
                    gross_pnl = EXCLUDED.gross_pnl,
                    funding = EXCLUDED.funding,
                    liquidation_fee = EXCLUDED.liquidation_fee,
                    insurance = EXCLUDED.insurance,
                    updated_at = EXCLUDED.updated_at
                """,
                pos.positionId(),
                exitPrice,
                openFee,
                closeFeeCol,
                grossPnl,
                settledFunding,
                liqFeeCol,
                insurance,
                Timestamp.from(now)
        );
        BigDecimal netPnl = grossPnl.add(settledFunding).subtract(openFee).subtract(closingFee);
        return new SettlementOutcome(closingFee, insurance, Decimal.cash(netPnl));
    }

    @Transactional
    public ReportingSnapshot snapshotForOwner(UUID ownerId, UUID sessionIdFilter) {
        ensureTables();
        HistoricalCandleProvider catchUpProvider = historicalCatchUpEnabled ? catalog::fetchHistoricalCandles : null;
        processAutomaticExits(ownerId, sessionIdFilter, catalog::fetchLiveMarkPriceIfAvailable, catchUpProvider, clock.instant());
        var sql = new StringBuilder("""
                SELECT
                    s.id,
                    s.owner_id,
                    s.mode,
                    s.initial_deposit,
                    s.engine_sequence,
                    COALESCE(m.deleted, false) AS is_deleted,
                    COALESCE((
                        SELECT w.balance
                        FROM wallet_accounts w
                        WHERE w.session_id = s.id AND w.kind = 'AVAILABLE'
                        LIMIT 1
                    ), s.initial_deposit) AS available_balance,
                    COALESCE((
                        SELECT SUM(o.reserved_amount)
                        FROM orders o
                        WHERE o.session_id = s.id AND o.status = 'NEW' AND o.reserved_amount > 0
                    ), 0) AS reserved_balance
                FROM simulation_sessions s
                LEFT JOIN session_metadata m ON m.session_id = s.id
                WHERE s.owner_id = ?
                """);
        List<Object> params = new ArrayList<>();
        params.add(ownerId);
        if (sessionIdFilter != null) {
            sql.append(" AND s.id = ?");
            params.add(sessionIdFilter);
        } else {
            sql.append(" ORDER BY s.created_at DESC");
        }

        List<SessionRow> rows = jdbc.query(
                sql.toString(),
                (rs, n) -> new SessionRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("owner_id", UUID.class),
                        rs.getString("mode"),
                        rs.getBigDecimal("initial_deposit"),
                        rs.getLong("engine_sequence"),
                        rs.getBoolean("is_deleted"),
                        rs.getBigDecimal("available_balance"),
                        rs.getBigDecimal("reserved_balance")
                ),
                params.toArray()
        );

        if (sessionIdFilter != null && (rows.isEmpty() || !"LIVE_PAPER".equals(rows.getFirst().mode()))) {
            throw new BusinessException(BusinessException.Code.NOT_FOUND);
        }

        Instant now = clock.instant();
        List<SessionSnapshot> sessions = new ArrayList<>();
        List<UUID> deletedSessionIds = new ArrayList<>();
        Map<UUID, SessionRow> activeById = new HashMap<>();

        for (SessionRow row : rows) {
            if (!"LIVE_PAPER".equals(row.mode())) {
                continue;
            }
            if (row.deleted()) {
                deletedSessionIds.add(row.sessionId());
                continue;
            }
            activeById.put(row.sessionId(), row);
        }

        if (activeById.isEmpty()) {
            return new ReportingSnapshot(ownerId, List.of(), deletedSessionIds, List.of());
        }

        Map<UUID, BigDecimal> isolatedBySession = new HashMap<>();
        Map<UUID, BigDecimal> unrealizedBySession = new HashMap<>();
        List<TradeSnapshot> trades = new ArrayList<>();

        for (SessionRow session : activeById.values()) {
            isolatedBySession.put(session.sessionId(), BigDecimal.ZERO);
            unrealizedBySession.put(session.sessionId(), BigDecimal.ZERO);
        }

        for (UUID sid : activeById.keySet()) {
            jdbc.query("""
                    SELECT
                        p.id,
                        p.session_id,
                        p.instrument_id,
                        p.side,
                        p.q,
                        p.entry_price,
                        p.initial_margin_remaining,
                        p.state,
                        p.opened_at,
                        p.closed_at,
                        o.position_id IS NOT NULL AS has_outcome,
                        o.open_fee,
                        o.close_fee,
                        o.gross_pnl,
                        o.funding AS outcome_funding,
                        o.liquidation_fee,
                        o.insurance,
                        COALESCE((
                            SELECT (ep.canonical_json->>'openFeeEstimate')::numeric
                            FROM orders ord
                            JOIN commands cmd ON cmd.id = ord.command_id
                            JOIN execution_plans ep
                              ON cmd.type = 'SUBMIT'
                             AND cmd.payload->>'planId' IS NOT NULL
                             AND ep.plan_id = (cmd.payload->>'planId')::uuid
                             AND ep.revision = COALESCE(NULLIF(cmd.payload->>'revision', '')::int, 1)
                            WHERE ord.position_id = p.id
                            ORDER BY ord.created_at ASC
                            LIMIT 1
                        ), 0) AS plan_open_fee,
                        COALESCE((
                            SELECT SUM(fs.amount)
                            FROM funding_settlements fs
                            WHERE fs.position_id = p.id
                        ), 0) AS settled_funding
                    FROM positions p
                    LEFT JOIN position_trade_outcomes o ON o.position_id = p.id
                    WHERE p.session_id = ?
                    ORDER BY p.opened_at DESC, p.id DESC
                    """,
                    rs -> {
                        UUID posId = rs.getObject("id", UUID.class);
                        UUID rowSessionId = rs.getObject("session_id", UUID.class);
                        UUID instrumentId = rs.getObject("instrument_id", UUID.class);
                        String side = rs.getString("side");
                        BigDecimal q = rs.getBigDecimal("q");
                        BigDecimal entry = rs.getBigDecimal("entry_price");
                        BigDecimal margin = rs.getBigDecimal("initial_margin_remaining");
                        String state = rs.getString("state");
                        Instant openedAt = rs.getTimestamp("opened_at").toInstant();
                        Timestamp closedTs = rs.getTimestamp("closed_at");
                        Instant closedAt = closedTs != null ? closedTs.toInstant() : null;
                        boolean hasOutcome = rs.getBoolean("has_outcome");
                        BigDecimal openFee = hasOutcome ? rs.getBigDecimal("open_fee") : rs.getBigDecimal("plan_open_fee");
                        BigDecimal closeFee = hasOutcome ? rs.getBigDecimal("close_fee") : BigDecimal.ZERO;
                        BigDecimal totalFees = (openFee != null ? openFee : BigDecimal.ZERO)
                                .add(closeFee != null ? closeFee : BigDecimal.ZERO);
                        BigDecimal gross = hasOutcome && rs.getBigDecimal("gross_pnl") != null
                                ? rs.getBigDecimal("gross_pnl")
                                : BigDecimal.ZERO;
                        BigDecimal funding = hasOutcome && rs.getBigDecimal("outcome_funding") != null
                                ? rs.getBigDecimal("outcome_funding")
                                : rs.getBigDecimal("settled_funding");
                        BigDecimal liqFee = hasOutcome && rs.getBigDecimal("liquidation_fee") != null
                                ? rs.getBigDecimal("liquidation_fee")
                                : BigDecimal.ZERO;
                        BigDecimal insurance = hasOutcome && rs.getBigDecimal("insurance") != null
                                ? rs.getBigDecimal("insurance")
                                : BigDecimal.ZERO;

                        if ("OPEN".equals(state) || "LIQUIDATING".equals(state)) {
                            BigDecimal mark = catalog.fetchLiveMarkPrice(instrumentId, entry);
                            BigDecimal diff = "LONG".equals(side)
                                    ? mark.subtract(entry)
                                    : entry.subtract(mark);
                            BigDecimal unreal = diff.multiply(q);
                            isolatedBySession.merge(rowSessionId, margin != null ? margin : BigDecimal.ZERO, BigDecimal::add);
                            unrealizedBySession.merge(rowSessionId, unreal, BigDecimal::add);
                            closedAt = null;
                        }

                        trades.add(new TradeSnapshot(
                                posId,
                                rowSessionId,
                                instrumentId,
                                catalog.resolveSymbol(instrumentId),
                                side,
                                state,
                                openedAt,
                                closedAt,
                                Decimal.wire(gross != null ? gross : BigDecimal.ZERO),
                                Decimal.wire(totalFees),
                                Decimal.wire(funding != null ? funding : BigDecimal.ZERO),
                                Decimal.wire(liqFee),
                                Decimal.wire(insurance)
                        ));
                    },
                    sid
            );
        }

        for (SessionRow row : rows) {
            if (!activeById.containsKey(row.sessionId())) {
                continue;
            }
            BigDecimal isolated = isolatedBySession.getOrDefault(row.sessionId(), BigDecimal.ZERO);
            BigDecimal unreal = unrealizedBySession.getOrDefault(row.sessionId(), BigDecimal.ZERO);
            BigDecimal available = row.availableBalance() != null ? row.availableBalance() : row.initialDeposit();
            BigDecimal rawReserved = row.reservedBalance();
            BigDecimal reserved = rawReserved != null && rawReserved.signum() > 0 ? rawReserved : BigDecimal.ZERO;
            BigDecimal equity = available.add(isolated).add(reserved).add(unreal);
            sessions.add(new SessionSnapshot(
                    row.sessionId(),
                    row.ownerId(),
                    "LIVE_PAPER",
                    "USDT",
                    Math.max(1L, row.engineSequence()),
                    Decimal.wire(equity),
                    Decimal.wire(unreal),
                    now
            ));
        }

        return new ReportingSnapshot(ownerId, sessions, deletedSessionIds, trades);
    }
}
