package com.crypto.reportingservice.infrastructure.repository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Repository
public class AccountSummaryQueryRepository {

    public record SummaryView(
            UUID ownerId,
            UUID sessionId,
            String mode,
            String currency,
            String realizedPnl,
            String totalFees,
            String signedFunding,
            String liquidationFees,
            String netRealizedPnl,
            String unrealizedPnl,
            String totalNetPnl,
            String insuranceAdjustment,
            Long closedTradeCount,
            String coverage,
            Instant projectionAsOf,
            Long lagMs
    ) {}

    public record TradeView(
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
            String netRealizedPnl,
            String insuranceAdjustment
    ) {}

    public record TradeHistoryView(
            UUID ownerId,
            UUID sessionId,
            String mode,
            String currency,
            List<TradeView> items,
            String nextCursor,
            String coverage,
            Instant projectionAsOf
    ) {}

    private final NamedParameterJdbcTemplate namedDb;
    private final byte[] cursorSecret;

    public AccountSummaryQueryRepository(
            NamedParameterJdbcTemplate namedDb,
            @Value("${reporting.cursor-secret:cryptolab-reporting-cursor-hmac-secret-2026}") String secret) {
        this.namedDb = namedDb;
        this.cursorSecret = secret.getBytes(StandardCharsets.UTF_8);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SummaryView querySummary(UUID subject, UUID sessionId) {
        verifySessionOwnershipIfSelected(subject, sessionId);

        var params = new MapSqlParameterSource()
                .addValue("subject", subject)
                .addValue("sessionId", sessionId);

        Integer incompleteCount = namedDb.queryForObject("""
                SELECT count(*) FROM session_owners s
                LEFT JOIN projection_checkpoints c ON c.session_id = s.session_id
                WHERE s.owner_id = :subject
                  AND (CAST(:sessionId AS uuid) IS NULL OR s.session_id = CAST(:sessionId AS uuid))
                  AND (s.mode IS NULL OR s.currency IS NULL OR c.session_id IS NULL)
                """, params, Integer.class);

        if (incompleteCount != null && incompleteCount > 0) {
            return new SummaryView(
                    subject, sessionId, "LIVE_PAPER", "USDT",
                    null, null, null, null, null, null, null, null,
                    null, "BUILDING", null, null
            );
        }

        var row = namedDb.queryForMap("""
                SELECT coalesce(sum(t.gross),0) AS realized_pnl,
                       coalesce(sum(t.fees),0) AS total_fees,
                       coalesce(sum(t.funding),0) AS signed_funding,
                       coalesce(sum(t.liquidation_fees),0) AS liquidation_fees,
                       coalesce(sum(t.gross-t.fees+t.funding-t.liquidation_fees),0) AS net_realized_pnl,
                       coalesce(sum(t.insurance),0) AS insurance_adjustment,
                       count(*) FILTER (WHERE t.status IN ('CLOSED','LIQUIDATED')) AS closed_trade_count
                FROM trade_summaries t
                JOIN session_owners s ON s.session_id=t.session_id
                WHERE s.owner_id=:subject AND s.mode='LIVE_PAPER' AND s.currency='USDT'
                  AND (CAST(:sessionId AS uuid) IS NULL OR s.session_id=CAST(:sessionId AS uuid))
                """, params);

        BigDecimal unrealized = namedDb.queryForObject("""
                SELECT coalesce(sum(latest.unrealized_pnl), 0)
                FROM session_owners s
                LEFT JOIN LATERAL (
                    SELECT e.unrealized_pnl
                    FROM equity_samples e
                    WHERE e.session_id = s.session_id
                    ORDER BY e.engine_sequence DESC
                    LIMIT 1
                ) latest ON true
                WHERE s.owner_id = :subject AND s.mode = 'LIVE_PAPER' AND s.currency = 'USDT'
                  AND (CAST(:sessionId AS uuid) IS NULL OR s.session_id = CAST(:sessionId AS uuid))
                """, params, BigDecimal.class);
        if (unrealized == null) {
            unrealized = BigDecimal.ZERO;
        }

        Timestamp minAsOfTs = namedDb.queryForObject("""
                SELECT min(c.as_of)
                FROM session_owners s
                JOIN projection_checkpoints c ON c.session_id = s.session_id
                WHERE s.owner_id = :subject AND s.mode = 'LIVE_PAPER' AND s.currency = 'USDT'
                  AND (CAST(:sessionId AS uuid) IS NULL OR s.session_id = CAST(:sessionId AS uuid))
                """, params, Timestamp.class);

        Instant asOf = minAsOfTs != null ? minAsOfTs.toInstant() : Instant.now();
        BigDecimal realized = (BigDecimal) row.get("realized_pnl");
        BigDecimal fees = (BigDecimal) row.get("total_fees");
        BigDecimal funding = (BigDecimal) row.get("signed_funding");
        BigDecimal liqFees = (BigDecimal) row.get("liquidation_fees");
        BigDecimal netRealized = (BigDecimal) row.get("net_realized_pnl");
        BigDecimal insurance = (BigDecimal) row.get("insurance_adjustment");
        Long closedCount = ((Number) row.get("closed_trade_count")).longValue();
        BigDecimal totalNet = netRealized.add(unrealized);

        return new SummaryView(
                subject,
                sessionId,
                "LIVE_PAPER",
                "USDT",
                fmt(realized),
                fmt(fees),
                fmt(funding),
                fmt(liqFees),
                fmt(netRealized),
                fmt(unrealized),
                fmt(totalNet),
                fmt(insurance),
                closedCount,
                "READY",
                asOf,
                0L
        );
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TradeHistoryView queryTradeHistory(UUID subject, UUID sessionId, String cursor, int limit) {
        if (limit < 1 || limit > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_QUERY");
        }
        verifySessionOwnershipIfSelected(subject, sessionId);

        Instant seekOpenedAt = null;
        UUID seekPositionId = null;
        if (cursor != null && !cursor.isBlank()) {
            var decoded = verifyAndDecodeCursor(cursor, subject, sessionId);
            seekOpenedAt = decoded.openedAt();
            seekPositionId = decoded.positionId();
        }

        var params = new MapSqlParameterSource()
                .addValue("subject", subject)
                .addValue("sessionId", sessionId)
                .addValue("seekOpenedAt", seekOpenedAt != null ? Timestamp.from(seekOpenedAt) : null)
                .addValue("seekPositionId", seekPositionId)
                .addValue("fetchLimit", limit + 1);

        List<TradeView> rows = namedDb.query("""
                SELECT t.position_id, t.session_id, t.instrument_id, t.symbol, t.side, t.status,
                       t.opened_at, t.closed_at, t.gross, t.fees, t.funding, t.liquidation_fees,
                       (t.gross - t.fees + t.funding - t.liquidation_fees) AS net_realized,
                       t.insurance
                FROM trade_summaries t
                JOIN session_owners s ON s.session_id = t.session_id
                WHERE s.owner_id = :subject AND s.mode = 'LIVE_PAPER' AND s.currency = 'USDT'
                  AND (CAST(:sessionId AS uuid) IS NULL OR s.session_id = CAST(:sessionId AS uuid))
                  AND (CAST(:seekOpenedAt AS timestamptz) IS NULL
                       OR (t.opened_at, t.position_id) < (CAST(:seekOpenedAt AS timestamptz), CAST(:seekPositionId AS uuid)))
                ORDER BY t.opened_at DESC, t.position_id DESC
                LIMIT :fetchLimit
                """, params, (rs, n) -> new TradeView(
                rs.getObject("position_id", UUID.class),
                rs.getObject("session_id", UUID.class),
                rs.getObject("instrument_id", UUID.class),
                rs.getString("symbol"),
                rs.getString("side"),
                rs.getString("status"),
                rs.getTimestamp("opened_at") != null ? rs.getTimestamp("opened_at").toInstant() : Instant.EPOCH,
                rs.getTimestamp("closed_at") != null ? rs.getTimestamp("closed_at").toInstant() : null,
                fmt(rs.getBigDecimal("gross")),
                fmt(rs.getBigDecimal("fees")),
                fmt(rs.getBigDecimal("funding")),
                fmt(rs.getBigDecimal("liquidation_fees")),
                fmt(rs.getBigDecimal("net_realized")),
                fmt(rs.getBigDecimal("insurance"))
        ));

        String nextCursor = null;
        List<TradeView> pageItems = rows;
        if (rows.size() > limit) {
            pageItems = new ArrayList<>(rows.subList(0, limit));
            TradeView last = pageItems.getLast();
            nextCursor = encodeCursor(subject, sessionId, last.openedAt(), last.positionId());
        }

        return new TradeHistoryView(
                subject,
                sessionId,
                "LIVE_PAPER",
                "USDT",
                pageItems,
                nextCursor,
                "READY",
                Instant.now()
        );
    }

    private void verifySessionOwnershipIfSelected(UUID subject, UUID sessionId) {
        if (sessionId == null) {
            return;
        }
        var params = new MapSqlParameterSource()
                .addValue("subject", subject)
                .addValue("sessionId", sessionId);
        Integer owned = namedDb.queryForObject("""
                SELECT count(*) FROM session_owners
                WHERE session_id = :sessionId AND owner_id = :subject AND mode = 'LIVE_PAPER' AND currency = 'USDT'
                """, params, Integer.class);
        if (owned == null || owned == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "NOT_FOUND");
        }
    }

    private record DecodedCursor(Instant openedAt, UUID positionId) {}

    public String encodeCursor(UUID owner, UUID session, Instant openedAt, UUID positionId) {
        String raw = owner + "|" + (session != null ? session : "ALL") + "|" + openedAt.toEpochMilli() + "|" + positionId;
        String payloadB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        String sig = hmacHex(payloadB64);
        return payloadB64 + "." + sig;
    }

    private DecodedCursor verifyAndDecodeCursor(String cursor, UUID expectedOwner, UUID expectedSession) {
        int dot = cursor.indexOf('.');
        if (dot <= 0 || dot == cursor.length() - 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR");
        }
        String payloadB64 = cursor.substring(0, dot);
        String givenSig = cursor.substring(dot + 1);
        String expectedSig = hmacHex(payloadB64);
        if (!MessageDigest.isEqual(givenSig.getBytes(StandardCharsets.UTF_8), expectedSig.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR_SIGNATURE");
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(payloadB64), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", 4);
            if (parts.length != 4) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR");
            }
            UUID owner = UUID.fromString(parts[0]);
            UUID session = "ALL".equals(parts[1]) ? null : UUID.fromString(parts[1]);
            if (!owner.equals(expectedOwner) || !java.util.Objects.equals(session, expectedSession)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CURSOR_SCOPE_MISMATCH");
            }
            Instant openedAt = Instant.ofEpochMilli(Long.parseLong(parts[2]));
            UUID positionId = UUID.fromString(parts[3]);
            return new DecodedCursor(openedAt, positionId);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR");
        }
    }

    private String hmacHex(String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(cursorSecret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("CURSOR_HMAC_ERROR", e);
        }
    }

    private static String fmt(BigDecimal val) {
        if (val == null) return "0";
        BigDecimal stripped = val.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0).toPlainString() : stripped.toPlainString();
    }
}

