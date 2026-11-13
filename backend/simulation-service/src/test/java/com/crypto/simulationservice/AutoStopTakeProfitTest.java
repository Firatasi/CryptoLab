package com.crypto.simulationservice;

import com.crypto.simulationservice.api.controller.SessionController;
import com.crypto.simulationservice.api.exception.ApiErrors;
import com.crypto.simulationservice.application.service.CreateSession;
import com.crypto.simulationservice.application.service.SimulationReportingService;
import com.crypto.simulationservice.application.service.SimulationReportingService.AutoExitReason;
import com.crypto.simulationservice.domain.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class AutoStopTakeProfitTest {

    private static final UUID RULE_PROFILE = UUID.fromString("88888888-8888-4888-8888-888888888888");
    private static final UUID RISK_POLICY_50X = UUID.fromString("99999999-9999-4999-8999-999999999997");
    private static final UUID BTC_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID ETH_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @Autowired
    CreateSession createSession;

    @Autowired
    SimulationReportingService reportingService;

    @Autowired
    SessionController sessionController;

    @Autowired
    ApiErrors apiErrors;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @Transactional
    void blankStopLossAndTakeProfitNeverAutoTriggerAcrossSafePriceSwings() {
        UUID owner = UUID.randomUUID();
        UUID sessionId = createSession.execute(owner, new BigDecimal("1000"), RULE_PROFILE, RISK_POLICY_50X);
        Instant t0 = Instant.parse("2026-11-13T02:00:00Z");

        UUID longPosId = insertPosition(sessionId, BTC_ID, "LONG", "0.1", "80000", "160", null, null, t0);
        UUID shortPosId = insertPosition(sessionId, ETH_ID, "SHORT", "1", "2500", "50", null, null, t0);

        // Large non-liquidating favorable & unfavorable swings must keep blank-bracket positions OPEN.
        var downSwing = reportingService.processAutomaticExits(
                owner,
                sessionId,
                id -> id.equals(BTC_ID) ? Optional.of(new BigDecimal("79100")) : Optional.of(new BigDecimal("2100")),
                t0.plusSeconds(10)
        );
        assertTrue(downSwing.isEmpty());

        var upSwing = reportingService.processAutomaticExits(
                owner,
                sessionId,
                id -> id.equals(BTC_ID) ? Optional.of(new BigDecimal("92000")) : Optional.of(new BigDecimal("2520")),
                t0.plusSeconds(20)
        );
        assertTrue(upSwing.isEmpty());

        assertEquals("OPEN", jdbc.queryForObject("SELECT state FROM positions WHERE id = ?", String.class, longPosId));
        assertEquals("OPEN", jdbc.queryForObject("SELECT state FROM positions WHERE id = ?", String.class, shortPosId));
    }

    @Test
    @Transactional
    void stopLossAutoClosesLongAndShortAsClosedWithCommandAuditAndCloseFee() {
        UUID owner = UUID.randomUUID();
        UUID sessionId = createSession.execute(owner, new BigDecimal("1000"), RULE_PROFILE, RISK_POLICY_50X);
        Instant t0 = Instant.parse("2026-11-13T03:00:00Z");

        // LONG entry 80000, liq ~78794 -> SL at 79500 (safe from liquidation, real SL trigger)
        UUID longPosId = insertPosition(sessionId, BTC_ID, "LONG", "0.1", "80000", "160", "79500", "85000", t0);
        // SHORT entry 2500, liq ~2537 -> SL at 2525 (safe from liquidation, real SL trigger)
        UUID shortPosId = insertPosition(sessionId, ETH_ID, "SHORT", "1", "2500", "50", "2525", "2200", t0);

        // Above LONG SL and below SHORT SL -> nothing happens
        assertTrue(reportingService.processAutomaticExits(
                owner,
                sessionId,
                id -> id.equals(BTC_ID) ? Optional.of(new BigDecimal("79600")) : Optional.of(new BigDecimal("2515")),
                t0.plusSeconds(5)
        ).isEmpty());

        // Cross both stop losses simultaneously
        var events = reportingService.processAutomaticExits(
                owner,
                sessionId,
                id -> id.equals(BTC_ID) ? Optional.of(new BigDecimal("79450")) : Optional.of(new BigDecimal("2526")),
                t0.plusSeconds(15)
        );

        assertEquals(2, events.size());
        assertTrue(events.stream().allMatch(ev -> ev.reason() == AutoExitReason.STOP_LOSS));
        assertEquals("CLOSED", jdbc.queryForObject("SELECT state FROM positions WHERE id = ?", String.class, longPosId));
        assertEquals("CLOSED", jdbc.queryForObject("SELECT state FROM positions WHERE id = ?", String.class, shortPosId));

        Integer cmdCount = jdbc.queryForObject("""
                SELECT count(*) FROM commands
                WHERE session_id = ? AND type = 'CLOSE_POSITION' AND status = 'FILLED' AND reason = 'STOP_LOSS'
                """, Integer.class, sessionId);
        assertEquals(2, cmdCount);

        var longOutcome = jdbc.queryForMap(
                "SELECT close_fee, liquidation_fee FROM position_trade_outcomes WHERE position_id = ?",
                longPosId
        );
        assertTrue(((BigDecimal) longOutcome.get("close_fee")).signum() > 0);
        assertEquals(0, ((BigDecimal) longOutcome.get("liquidation_fee")).compareTo(BigDecimal.ZERO));

        var snap = reportingService.snapshotForOwner(owner, sessionId);
        assertEquals(2, snap.trades().size());
        assertTrue(snap.trades().stream().allMatch(t -> "CLOSED".equals(t.status())));
    }

    @Test
    @Transactional
    void takeProfitAutoClosesLongAndShortWithProfitsCreditedToWallet() {
        UUID owner = UUID.randomUUID();
        UUID sessionId = createSession.execute(owner, new BigDecimal("1000"), RULE_PROFILE, RISK_POLICY_50X);
        Instant t0 = Instant.parse("2026-11-13T04:00:00Z");

        UUID longPosId = insertPosition(sessionId, BTC_ID, "LONG", "0.1", "80000", "160", "79400", "82000", t0);
        UUID shortPosId = insertPosition(sessionId, ETH_ID, "SHORT", "1", "2500", "50", "2525", "2400", t0);

        BigDecimal balanceBefore = jdbc.queryForObject(
                "SELECT balance FROM wallet_accounts WHERE session_id = ? AND kind = 'AVAILABLE'",
                BigDecimal.class,
                sessionId
        );

        var events = reportingService.processAutomaticExits(
                owner,
                sessionId,
                id -> id.equals(BTC_ID) ? Optional.of(new BigDecimal("82100")) : Optional.of(new BigDecimal("2390")),
                t0.plusSeconds(30)
        );

        assertEquals(2, events.size());
        assertTrue(events.stream().allMatch(ev -> ev.reason() == AutoExitReason.TAKE_PROFIT));
        assertEquals("CLOSED", jdbc.queryForObject("SELECT state FROM positions WHERE id = ?", String.class, longPosId));
        assertEquals("CLOSED", jdbc.queryForObject("SELECT state FROM positions WHERE id = ?", String.class, shortPosId));

        BigDecimal balanceAfter = jdbc.queryForObject(
                "SELECT balance FROM wallet_accounts WHERE session_id = ? AND kind = 'AVAILABLE'",
                BigDecimal.class,
                sessionId
        );
        // Returned original margin (160 + 50) + positive TP profits (~210 + ~110) - small taker fees
        assertTrue(balanceAfter.compareTo(balanceBefore.add(new BigDecimal("450"))) > 0);

        Integer tpCmdCount = jdbc.queryForObject("""
                SELECT count(*) FROM commands
                WHERE session_id = ? AND type = 'CLOSE_POSITION' AND status = 'FILLED' AND reason = 'TAKE_PROFIT'
                """, Integer.class, sessionId);
        assertEquals(2, tpCmdCount);
    }

    @Test
    @Transactional
    void liquidationTakesPriorityWhenBothLiquidationAndStopLossAreBreached() {
        UUID owner = UUID.randomUUID();
        UUID sessionId = createSession.execute(owner, new BigDecimal("1000"), RULE_PROFILE, RISK_POLICY_50X);
        Instant t0 = Instant.parse("2026-11-13T05:00:00Z");

        UUID longPosId = insertPosition(sessionId, BTC_ID, "LONG", "0.1", "80000", "160", "79400", "84000", t0);

        // Price gaps straight past both SL (79400) and liquidation (~78794) -> LIQUIDATED must win
        var events = reportingService.processAutomaticExits(
                owner,
                sessionId,
                id -> Optional.of(new BigDecimal("78400")),
                t0.plusSeconds(10)
        );

        assertEquals(1, events.size());
        assertEquals(AutoExitReason.LIQUIDATED, events.getFirst().reason());
        assertEquals("LIQUIDATED", jdbc.queryForObject("SELECT state FROM positions WHERE id = ?", String.class, longPosId));

        Integer liqCmdCount = jdbc.queryForObject("""
                SELECT count(*) FROM commands
                WHERE session_id = ? AND type = 'LIQUIDATE_POSITION' AND status = 'FILLED' AND reason = 'AUTO_LIQUIDATION'
                """, Integer.class, sessionId);
        assertEquals(1, liqCmdCount);
    }

    @Test
    @Transactional
    void updateBracketsValidatesDirectionAllowsClearingAndRejectsClosedPositions() {
        UUID owner = UUID.randomUUID();
        UUID sessionId = createSession.execute(owner, new BigDecimal("1000"), RULE_PROFILE, RISK_POLICY_50X);
        Instant t0 = Instant.parse("2026-11-13T06:00:00Z");

        UUID longPosId = insertPosition(sessionId, BTC_ID, "LONG", "0.1", "80000", "160", "79400", "84000", t0);
        Jwt jwt = Jwt.withTokenValue("test-jwt")
                .header("alg", "none")
                .subject(owner.toString())
                .build();

        // Clearing SL/TP to null succeeds cleanly -> persisted as SQL NULL
        Map<String, Object> clearBody = new HashMap<>();
        clearBody.put("stopLoss", null);
        clearBody.put("takeProfit", null);
        var cleared = sessionController.updateBrackets(jwt, longPosId, clearBody);
        assertNull(cleared.get("stopLoss"));
        assertNull(cleared.get("takeProfit"));

        // Inverted LONG stopLoss (>= mark) is rejected and mapped to HTTP 400 INVALID_STOP (not 500)
        var badStopEx = assertThrows(IllegalArgumentException.class, () ->
                sessionController.updateBrackets(jwt, longPosId, Map.of("stopLoss", "9999999"))
        );
        assertEquals("INVALID_STOP", badStopEx.getMessage());
        var stopResp = apiErrors.legacy(badStopEx);
        assertEquals(400, stopResp.getStatusCode().value());
        assertEquals("INVALID_STOP", stopResp.getBody().code());

        // Inverted LONG takeProfit (<= mark) is rejected and mapped to HTTP 400 INVALID_TARGET
        var badTargetEx = assertThrows(IllegalArgumentException.class, () ->
                sessionController.updateBrackets(jwt, longPosId, Map.of("takeProfit", "1"))
        );
        assertEquals("INVALID_TARGET", badTargetEx.getMessage());
        var targetResp = apiErrors.legacy(badTargetEx);
        assertEquals("INVALID_TARGET", targetResp.getBody().code());
        assertEquals(400, targetResp.getStatusCode().value());

        // Once position is CLOSED, updating brackets throws VERSION_CONFLICT (HTTP 409)
        jdbc.update("UPDATE positions SET state = 'CLOSED' WHERE id = ?", longPosId);
        var closedEx = assertThrows(BusinessException.class, () ->
                sessionController.updateBrackets(jwt, longPosId, clearBody)
        );
        assertEquals(BusinessException.Code.VERSION_CONFLICT, closedEx.code());
    }

    @Test
    @Transactional
    @SuppressWarnings("unchecked")
    void listSessionsReturnsWalletPnlMetricsUsingBigDecimalPrecision() {
        UUID owner = UUID.randomUUID();
        UUID activeSid = createSession.execute(owner, new BigDecimal("10000"), RULE_PROFILE, RISK_POLICY_50X);
        UUID idleSid = createSession.execute(owner, new BigDecimal("5000"), RULE_PROFILE, RISK_POLICY_50X);
        Instant t0 = Instant.parse("2026-11-13T07:00:00Z");

        jdbc.update(
                "UPDATE wallet_accounts SET balance = ? WHERE session_id = ? AND kind = 'AVAILABLE'",
                new BigDecimal("9728.1207"), activeSid
        );
        insertPosition(activeSid, BTC_ID, "LONG", "0.01", "68000", "249.8037", null, null, t0);

        Jwt jwt = Jwt.withTokenValue("test-jwt")
                .header("alg", "none")
                .subject(owner.toString())
                .build();

        Map<String, Object> body = sessionController.listSessions(jwt);
        var items = (java.util.List<Map<String, Object>>) body.get("items");
        assertEquals(2, items.size());

        Map<String, Object> activeRow = items.stream()
                .filter(m -> activeSid.toString().equals(m.get("id")))
                .findFirst()
                .orElseThrow();
        Map<String, Object> idleRow = items.stream()
                .filter(m -> idleSid.toString().equals(m.get("id")))
                .findFirst()
                .orElseThrow();

        // Active wallet realized change = 9728.1207 + 249.8037 - 10000 = -22.0756
        BigDecimal realized = new BigDecimal((String) activeRow.get("realizedPnl"));
        BigDecimal unrealized = new BigDecimal((String) activeRow.get("unrealizedPnl"));
        BigDecimal equity = new BigDecimal((String) activeRow.get("equity"));
        BigDecimal netPnl = new BigDecimal((String) activeRow.get("netPnl"));
        BigDecimal netPct = new BigDecimal((String) activeRow.get("netPnlPercent"));

        assertEquals(0, new BigDecimal("-22.0756").compareTo(realized));
        assertEquals(0, new BigDecimal("9728.1207").add(new BigDecimal("249.8037")).add(unrealized).compareTo(equity));
        assertEquals(0, realized.add(unrealized).compareTo(netPnl));
        assertEquals(0, netPnl.multiply(new BigDecimal("100"))
                .divide(new BigDecimal("10000"), 2, java.math.RoundingMode.HALF_UP)
                .compareTo(netPct));
        assertEquals(1, activeRow.get("openPositionCount"));

        // Idle wallet with 0 positions and untouched 5000 capital has exact zero PnL
        assertEquals("0", idleRow.get("realizedPnl"));
        assertEquals("0", idleRow.get("unrealizedPnl"));
        assertEquals("0", idleRow.get("netPnl"));
        assertEquals("0", idleRow.get("netPnlPercent"));
        assertEquals(0, idleRow.get("openPositionCount"));
    }

    private UUID insertPosition(
            UUID sessionId,
            UUID instrumentId,
            String side,
            String q,
            String entryPrice,
            String margin,
            String stopLoss,
            String takeProfit,
            Instant openedAt
    ) {
        UUID posId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO positions(
                    id, session_id, instrument_id, side, q, entry_price,
                    original_margin, initial_margin_remaining, state, generation,
                    version, stop_loss, take_profit, deadline_at, expiry_policy, horizon_seconds, close_requested, opened_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', 1, 1, ?, ?, ?, 'CLOSE_AT_DEADLINE', 3600, false, ?)
                """,
                posId,
                sessionId,
                instrumentId,
                side,
                new BigDecimal(q),
                new BigDecimal(entryPrice),
                new BigDecimal(margin),
                new BigDecimal(margin),
                stopLoss != null ? new BigDecimal(stopLoss) : null,
                takeProfit != null ? new BigDecimal(takeProfit) : null,
                Timestamp.from(openedAt.plusSeconds(3600)),
                Timestamp.from(openedAt)
        );
        return posId;
    }
}
