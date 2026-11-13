package com.crypto.simulationservice;

import com.crypto.simulationservice.application.service.CreateSession;
import com.crypto.simulationservice.application.service.LiquidationMonitorService;
import com.crypto.simulationservice.application.service.SimulationReportingService;
import com.crypto.simulationservice.infrastructure.streaming.SessionStreamRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class AutoLiquidationTest {

    private static final UUID RULE_PROFILE = UUID.fromString("88888888-8888-4888-8888-888888888888");
    private static final UUID RISK_POLICY_50X = UUID.fromString("99999999-9999-4999-8999-999999999997");
    private static final UUID BTC_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID ETH_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @Autowired
    CreateSession createSession;

    @Autowired
    SimulationReportingService reportingService;

    @Autowired
    SessionStreamRegistry streamRegistry;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Clock clock;

    @Test
    @Transactional
    void healthyPositionRemainsOpenAndBreachedLongShortAutoLiquidate() {
        UUID owner = UUID.randomUUID();
        UUID sessionId = createSession.execute(owner, new BigDecimal("1000"), RULE_PROFILE, RISK_POLICY_50X);
        Instant t0 = Instant.parse("2026-11-13T01:00:00Z");

        // 50x LONG BTC: q=0.1, entry=80000 -> notional=8000, margin=160 (~liq ~78794)
        UUID longPosId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO positions(
                    id, session_id, instrument_id, side, q, entry_price,
                    original_margin, initial_margin_remaining, state, generation,
                    version, deadline_at, expiry_policy, horizon_seconds, close_requested, opened_at
                )
                VALUES (?, ?, ?, 'LONG', 0.1, 80000, 160, 160, 'OPEN', 1, 1, ?, 'CLOSE_AT_DEADLINE', 3600, false, ?)
                """, longPosId, sessionId, BTC_ID, Timestamp.from(t0.plusSeconds(3600)), Timestamp.from(t0));

        // 50x SHORT ETH: q=1, entry=2500 -> notional=2500, margin=50 (~liq ~2537)
        UUID shortPosId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO positions(
                    id, session_id, instrument_id, side, q, entry_price,
                    original_margin, initial_margin_remaining, state, generation,
                    version, deadline_at, expiry_policy, horizon_seconds, close_requested, opened_at
                )
                VALUES (?, ?, ?, 'SHORT', 1, 2500, 50, 50, 'OPEN', 1, 1, ?, 'CLOSE_AT_DEADLINE', 3600, false, ?)
                """, shortPosId, sessionId, ETH_ID, Timestamp.from(t0.plusSeconds(3600)), Timestamp.from(t0));

        // Healthy prices -> neither liquidates
        var safeEvents = reportingService.liquidateBreachedPositions(
                owner,
                sessionId,
                id -> id.equals(BTC_ID)
                        ? Optional.of(new BigDecimal("79800"))
                        : Optional.of(new BigDecimal("2510")),
                t0.plusSeconds(10)
        );
        assertTrue(safeEvents.isEmpty());
        assertEquals("OPEN", jdbc.queryForObject("SELECT state FROM positions WHERE id = ?", String.class, longPosId));
        assertEquals("OPEN", jdbc.queryForObject("SELECT state FROM positions WHERE id = ?", String.class, shortPosId));

        // Breached BTC LONG (78500) + Breached ETH SHORT (2550) -> both auto-liquidate
        var liqEvents = reportingService.liquidateBreachedPositions(
                owner,
                sessionId,
                id -> id.equals(BTC_ID)
                        ? Optional.of(new BigDecimal("78500"))
                        : Optional.of(new BigDecimal("2550")),
                t0.plusSeconds(20)
        );
        assertEquals(2, liqEvents.size());
        assertEquals("LIQUIDATED", jdbc.queryForObject("SELECT state FROM positions WHERE id = ?", String.class, longPosId));
        assertEquals("LIQUIDATED", jdbc.queryForObject("SELECT state FROM positions WHERE id = ?", String.class, shortPosId));

        // Idempotent: second run returns empty
        var repeatEvents = reportingService.liquidateBreachedPositions(
                owner,
                sessionId,
                id -> Optional.of(new BigDecimal("50000")),
                t0.plusSeconds(30)
        );
        assertTrue(repeatEvents.isEmpty());

        // Commands audit row created for AUTO_LIQUIDATION
        Integer liqCmdCount = jdbc.queryForObject("""
                SELECT count(*) FROM commands
                WHERE session_id = ? AND type = 'LIQUIDATE_POSITION' AND status = 'FILLED' AND reason = 'AUTO_LIQUIDATION'
                """, Integer.class, sessionId);
        assertEquals(2, liqCmdCount);

        // Snapshot reflects LIQUIDATED trades with liquidationFees > 0
        var snap = reportingService.snapshotForOwner(owner, sessionId);
        assertEquals(2, snap.trades().size());
        for (var trade : snap.trades()) {
            assertEquals("LIQUIDATED", trade.status());
            assertNotNull(trade.closedAt());
            assertTrue(new BigDecimal(trade.liquidationFees()).signum() > 0);
        }

        // Disabled monitor sweep safely no-ops
        var disabledMonitor = new LiquidationMonitorService(reportingService, streamRegistry, clock, false);
        disabledMonitor.sweepOpenPositions();
    }
}
