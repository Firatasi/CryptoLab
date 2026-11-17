package com.crypto.simulationservice;

import com.crypto.simulationservice.api.controller.SessionController;
import com.crypto.simulationservice.application.service.CreateSession;
import com.crypto.simulationservice.application.service.SimulationMarketCatalog;
import com.crypto.simulationservice.application.service.SimulationMarketCatalog.HistoricalCandle;
import com.crypto.simulationservice.application.service.SimulationReportingService;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OfflineHistoricalCatchUpIT {

    private static final UUID BTC_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID RULE_PROFILE = UUID.fromString("88888888-8888-4888-8888-888888888888");
    private static final UUID RISK_POLICY_50X = UUID.fromString("99999999-9999-4999-8999-999999999999");

    @Autowired
    CreateSession createSession;

    @Autowired
    SimulationReportingService reportingService;

    @Autowired
    SimulationMarketCatalog catalog;

    @Autowired
    SessionController sessionController;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @Transactional
    void persistsDynamicSymbolAndIncludesPendingLimitReservedMarginAcrossCatchUp() {
        reportingService.ensureTables();
        UUID ownerId = UUID.randomUUID();
        UUID sessionId = createSession.execute(ownerId, new BigDecimal("10000"), RULE_PROFILE, RISK_POLICY_50X);

        // Dinamik altcoin sembol eşleşmesi PostgreSQL'e yazılır
        UUID wifId = catalog.resolveInstrumentUuid("WIFUSDT");
        String dbSym = jdbc.queryForObject(
                "SELECT symbol FROM instrument_symbol_cache WHERE instrument_id = ?",
                String.class,
                wifId
        );
        assertEquals("WIFUSDT", dbSym);

        // Bekleyen LIMIT emri rezervi /sessions özkaynak hesabında korunur
        jdbc.update("UPDATE wallet_accounts SET balance = balance - 2000 WHERE session_id = ? AND kind = 'AVAILABLE'", sessionId);
        UUID cmdId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO commands(id, session_id, type, payload, status, reason, created_at, updated_at)
                VALUES (?, ?, 'SUBMIT', '{}'::jsonb, 'FILLED', NULL, now(), now())
                """, cmdId, sessionId);
        jdbc.update("""
                INSERT INTO orders(id, session_id, command_id, instrument_id, side, type, q, limit_price, reserved_amount, status, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'LONG', 'LIMIT', 100, 2.0, 2000, 'NEW', 1, now(), now())
                """, UUID.randomUUID(), sessionId, cmdId, wifId);

        Jwt jwt = Jwt.withTokenValue("test-jwt").header("alg", "none").subject(ownerId.toString()).build();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sessions = (List<Map<String, Object>>) sessionController.listSessions(jwt).get("items");
        Map<String, Object> wallet = sessions.stream()
                .filter(s -> sessionId.toString().equals(s.get("id")))
                .findFirst()
                .orElseThrow();
        assertEquals("0", wallet.get("realizedPnl"));
        assertEquals(0, new BigDecimal("10000").compareTo(new BigDecimal((String) wallet.get("equity"))));

        // Çevrimdışı kapanan 15 dakikalık pozisyon işlem geçmişine ve bakiyeye işlenir
        Instant t0 = Instant.parse("2026-11-12T10:00:00Z");
        Instant now = Instant.parse("2026-11-13T10:00:00Z");
        UUID posId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO positions(
                    id, session_id, instrument_id, side, q, entry_price,
                    original_margin, initial_margin_remaining, state, generation,
                    version, stop_loss, take_profit, deadline_at, expiry_policy, horizon_seconds, close_requested, opened_at
                )
                VALUES (?, ?, ?, 'LONG', 0.1, 50000, 500, 500, 'OPEN', 1, 1, 49000, 51500, ?, 'CLOSE_AT_DEADLINE', 900, false, ?)
                """, posId, sessionId, BTC_ID, Timestamp.from(t0.plusSeconds(900)), Timestamp.from(t0));

        List<HistoricalCandle> bars = List.of(
                new HistoricalCandle(
                        Instant.parse("2026-11-12T10:07:00Z"),
                        Instant.parse("2026-11-12T10:07:59Z"),
                        new BigDecimal("50500"),
                        new BigDecimal("51700"),
                        new BigDecimal("50400"),
                        new BigDecimal("51600"),
                        true
                )
        );
        var events = reportingService.processAutomaticExits(
                ownerId, sessionId, id -> Optional.of(new BigDecimal("47000")), (s, iv, start, end) -> bars, now
        );
        assertEquals(1, events.size());
        assertEquals(SimulationReportingService.AutoExitReason.TAKE_PROFIT, events.getFirst().reason());
        assertEquals(Instant.parse("2026-11-12T10:07:59Z"), events.getFirst().closedAt());
    }
}
