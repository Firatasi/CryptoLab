package com.crypto.simulationservice.application.service;

import com.crypto.financecore.finance.Decimal;
import com.crypto.financecore.finance.Finance;
import com.crypto.financecore.finance.MaintenanceTier;
import com.crypto.financecore.finance.PositionSide;
import com.crypto.simulationservice.domain.exception.BusinessException;
import com.crypto.simulationservice.domain.service.RiskDecision;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
            BigDecimal settledFunding
    ) {}

    private record SessionRow(
            UUID sessionId,
            UUID ownerId,
            String mode,
            BigDecimal initialDeposit,
            long engineSequence,
            boolean deleted,
            BigDecimal availableBalance
    ) {}

    private final JdbcTemplate jdbc;
    private final SimulationMarketCatalog catalog;
    private final Clock clock;
    private volatile boolean tablesEnsured = false;

    public SimulationReportingService(JdbcTemplate jdbc, SimulationMarketCatalog catalog, Clock clock) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.clock = clock;
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
        return processAutomaticExits(ownerId, sessionIdFilter, catalog::fetchLiveMarkPriceIfAvailable, now);
    }

    @Transactional
    public List<PositionExitEvent> processAutomaticExits(
            UUID ownerId,
            UUID sessionIdFilter,
            Function<UUID, Optional<BigDecimal>> markPriceLookup,
            Instant now
    ) {
        ensureTables();
        List<OpenPositionRow> candidates = fetchOpenPositions(ownerId, sessionIdFilter, null, null, false);
        if (candidates.isEmpty()) {
            return List.of();
        }

        Map<UUID, Optional<BigDecimal>> resolvedMarks = new HashMap<>();
        List<PositionExitEvent> exitEvents = new ArrayList<>();

        for (OpenPositionRow candidate : candidates) {
            if (candidate.q() == null || candidate.q().signum() <= 0
                    || candidate.entryPrice() == null || candidate.entryPrice().signum() <= 0) {
                continue;
            }
            Optional<BigDecimal> markOpt = resolvedMarks.computeIfAbsent(candidate.instrumentId(), markPriceLookup);
            if (markOpt.isEmpty() || markOpt.get().signum() <= 0) {
                continue;
            }
            BigDecimal markPrice = markOpt.get();
            BigDecimal candidateMargin = candidate.margin() != null ? candidate.margin() : BigDecimal.ZERO;
            RiskDecision.Action candidateAction = RiskDecision.onMark(
                    PositionSide.valueOf(candidate.side()),
                    candidate.q(),
                    candidate.entryPrice(),
                    markPrice,
                    candidateMargin,
                    candidate.stopLoss(),
                    candidate.takeProfit(),
                    DEFAULT_MAINTENANCE_TIERS
            );
            if (candidateAction == RiskDecision.Action.NONE) {
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
            BigDecimal lockedMargin = locked.margin() != null ? locked.margin() : BigDecimal.ZERO;
            RiskDecision.Action lockedAction = RiskDecision.onMark(
                    PositionSide.valueOf(locked.side()),
                    locked.q(),
                    locked.entryPrice(),
                    markPrice,
                    lockedMargin,
                    locked.stopLoss(),
                    locked.takeProfit(),
                    DEFAULT_MAINTENANCE_TIERS
            );
            if (lockedAction == RiskDecision.Action.NONE) {
                continue;
            }

            boolean liquidated = lockedAction == RiskDecision.Action.LIQUIDATE;
            BigDecimal feeRate = liquidated
                    ? DEFAULT_LIQUIDATION_FEE_RATE
                    : SimulationMarketCatalog.resolveTakerFeeRate(locked.ruleProfileId(), "MARKET");

            SettlementOutcome outcome = settlePosition(locked, liquidated, markPrice, feeRate, now);
            if (outcome == null) {
                continue;
            }

            String cmdType = liquidated ? "LIQUIDATE_POSITION" : "CLOSE_POSITION";
            String cmdReason = liquidated ? "AUTO_LIQUIDATION" : lockedAction.name();
            UUID commandId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO commands(id, session_id, type, payload, status, reason, created_at, updated_at)
                    VALUES (?, ?, ?, '{}'::jsonb, 'FILLED', ?, ?, ?)
                    """, commandId, locked.sessionId(), cmdType, cmdReason, Timestamp.from(now), Timestamp.from(now));

            AutoExitReason reason = liquidated
                    ? AutoExitReason.LIQUIDATED
                    : (lockedAction == RiskDecision.Action.STOP_LOSS
                            ? AutoExitReason.STOP_LOSS
                            : AutoExitReason.TAKE_PROFIT);

            exitEvents.add(new PositionExitEvent(
                    locked.sessionId(),
                    locked.positionId(),
                    locked.instrumentId(),
                    catalog.resolveSymbol(locked.instrumentId()),
                    locked.side(),
                    reason,
                    markPrice,
                    outcome.closingFee(),
                    outcome.insurance(),
                    outcome.realizedPnl(),
                    now
            ));
        }

        return exitEvents;
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
                        rs.getBigDecimal("settled_funding")
                ),
                params.toArray()
        );
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
        processAutomaticExits(ownerId, sessionIdFilter, catalog::fetchLiveMarkPriceIfAvailable, clock.instant());
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
                    ), s.initial_deposit) AS available_balance
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
                        rs.getBigDecimal("available_balance")
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
            BigDecimal equity = available.add(isolated).add(unreal);
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
