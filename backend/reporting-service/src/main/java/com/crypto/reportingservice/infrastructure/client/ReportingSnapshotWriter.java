package com.crypto.reportingservice.infrastructure.client;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class ReportingSnapshotWriter {

    public record SessionSnapshot(
            UUID sessionId,
            UUID ownerId,
            String mode,
            String currency,
            long engineSequence,
            BigDecimal equity,
            BigDecimal unrealizedPnl,
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
            BigDecimal realizedPnl,
            BigDecimal totalFees,
            BigDecimal signedFunding,
            BigDecimal liquidationFees,
            BigDecimal insuranceAdjustment
    ) {}

    public record SnapshotPayload(
            UUID ownerId,
            List<SessionSnapshot> sessions,
            List<UUID> deletedSessionIds,
            List<TradeSnapshot> trades
    ) {}

    private final JdbcTemplate db;

    public ReportingSnapshotWriter(JdbcTemplate db) {
        this.db = db;
    }

    @Transactional
    public void persistSnapshot(UUID expectedSubject, SnapshotPayload payload) {
        if (payload == null) {
            return;
        }
        if (payload.deletedSessionIds() != null) {
            for (UUID deletedId : payload.deletedSessionIds()) {
                if (deletedId == null) {
                    continue;
                }
                Integer owned = db.queryForObject(
                        "SELECT count(*) FROM session_owners WHERE session_id = ? AND owner_id = ?",
                        Integer.class,
                        deletedId,
                        expectedSubject
                );
                if (owned != null && owned > 0) {
                    db.update("DELETE FROM trade_summaries WHERE session_id = ?", deletedId);
                    db.update("DELETE FROM equity_samples WHERE session_id = ?", deletedId);
                    db.update("DELETE FROM projection_checkpoints WHERE session_id = ?", deletedId);
                    db.update("DELETE FROM session_owners WHERE session_id = ? AND owner_id = ?", deletedId, expectedSubject);
                }
            }
        }

        Set<UUID> syncedSessions = new HashSet<>();
        if (payload.sessions() != null) {
            for (SessionSnapshot s : payload.sessions()) {
                if (s == null || s.sessionId() == null || !expectedSubject.equals(s.ownerId())
                        || !"LIVE_PAPER".equals(s.mode()) || !"USDT".equals(s.currency())) {
                    continue;
                }
                syncedSessions.add(s.sessionId());
                Timestamp asOfTs = Timestamp.from(s.asOf() != null ? s.asOf() : Instant.now());
                long seq = Math.max(1L, s.engineSequence());

                db.update("""
                        INSERT INTO session_owners(session_id, owner_id, mode, currency)
                        VALUES (?, ?, 'LIVE_PAPER', 'USDT')
                        ON CONFLICT (session_id) DO UPDATE SET
                            owner_id = EXCLUDED.owner_id,
                            mode = EXCLUDED.mode,
                            currency = EXCLUDED.currency
                        """, s.sessionId(), expectedSubject);

                db.update("""
                        INSERT INTO projection_checkpoints(session_id, last_sequence, as_of)
                        VALUES (?, ?, ?)
                        ON CONFLICT (session_id) DO UPDATE SET
                            last_sequence = GREATEST(projection_checkpoints.last_sequence, EXCLUDED.last_sequence),
                            as_of = EXCLUDED.as_of
                        """, s.sessionId(), seq, asOfTs);

                Long latestSeq = db.queryForObject(
                        "SELECT max(engine_sequence) FROM equity_samples WHERE session_id = ?",
                        Long.class,
                        s.sessionId()
                );
                BigDecimal equity = s.equity() != null ? s.equity() : BigDecimal.ZERO;
                BigDecimal unrealized = s.unrealizedPnl() != null ? s.unrealizedPnl() : BigDecimal.ZERO;
                if (latestSeq == null) {
                    db.update("""
                            INSERT INTO equity_samples(session_id, engine_sequence, as_of, equity, unrealized_pnl, quality)
                            VALUES (?, ?, ?, ?, ?, 'FRESH')
                            ON CONFLICT (session_id, engine_sequence) DO UPDATE SET
                                as_of = EXCLUDED.as_of,
                                equity = EXCLUDED.equity,
                                unrealized_pnl = EXCLUDED.unrealized_pnl,
                                quality = EXCLUDED.quality
                            """, s.sessionId(), seq, asOfTs, equity, unrealized);
                } else {
                    db.update("""
                            UPDATE equity_samples
                            SET as_of = ?, equity = ?, unrealized_pnl = ?, quality = 'FRESH'
                            WHERE session_id = ? AND engine_sequence = ?
                            """, asOfTs, equity, unrealized, s.sessionId(), latestSeq);
                }
            }
        }

        if (payload.trades() != null) {
            for (TradeSnapshot t : payload.trades()) {
                if (t == null || t.positionId() == null || !syncedSessions.contains(t.sessionId())) {
                    continue;
                }
                Timestamp openedTs = Timestamp.from(t.openedAt() != null ? t.openedAt() : Instant.now());
                Timestamp closedTs = t.closedAt() != null ? Timestamp.from(t.closedAt()) : null;
                db.update("""
                        INSERT INTO trade_summaries(
                            position_id, session_id, instrument_id, symbol, side, status,
                            opened_at, closed_at, gross, fees, funding, liquidation_fees, insurance
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (position_id) DO UPDATE SET
                            instrument_id = EXCLUDED.instrument_id,
                            symbol = EXCLUDED.symbol,
                            side = EXCLUDED.side,
                            status = EXCLUDED.status,
                            opened_at = EXCLUDED.opened_at,
                            closed_at = EXCLUDED.closed_at,
                            gross = EXCLUDED.gross,
                            fees = EXCLUDED.fees,
                            funding = EXCLUDED.funding,
                            liquidation_fees = EXCLUDED.liquidation_fees,
                            insurance = EXCLUDED.insurance
                        """,
                        t.positionId(),
                        t.sessionId(),
                        t.instrumentId(),
                        t.symbol(),
                        t.side(),
                        t.status(),
                        openedTs,
                        closedTs,
                        zeroIfNull(t.realizedPnl()),
                        zeroIfNull(t.totalFees()),
                        zeroIfNull(t.signedFunding()),
                        zeroIfNull(t.liquidationFees()),
                        zeroIfNull(t.insuranceAdjustment())
                );
            }
        }
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
