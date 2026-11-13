package com.crypto.simulationservice;

import com.crypto.simulationservice.application.service.CreateSession;
import com.crypto.simulationservice.application.service.SimulationReportingService;
import com.crypto.simulationservice.domain.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class ReportingSnapshotAndCloseTest {

    private static final UUID RULE_PROFILE = UUID.fromString("88888888-8888-4888-8888-888888888888");
    private static final UUID RISK_POLICY = UUID.fromString("99999999-9999-4999-8999-999999999999");
    private static final UUID BTC_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

    @Autowired
    CreateSession createSession;

    @Autowired
    SimulationReportingService reportingService;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @Transactional
    void snapshotForOwnerIncludesEmptySessionRejectsForeignOwnerAndRecordsCloseOutcome() {
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        UUID sessionId = createSession.execute(owner, new BigDecimal("10000"), RULE_PROFILE, RISK_POLICY);

        var emptySnap = reportingService.snapshotForOwner(owner, sessionId);
        assertEquals(1, emptySnap.sessions().size());
        assertEquals(sessionId, emptySnap.sessions().getFirst().sessionId());
        assertTrue(emptySnap.trades().isEmpty());

        var ex = assertThrows(BusinessException.class,
                () -> reportingService.snapshotForOwner(stranger, sessionId));
        assertEquals(BusinessException.Code.NOT_FOUND, ex.code());

        UUID posId = UUID.randomUUID();
        Instant openedAt = Instant.now().minusSeconds(60);
        jdbc.update("""
                INSERT INTO positions(
                    id, session_id, instrument_id, side, q, entry_price,
                    original_margin, initial_margin_remaining, state, generation,
                    version, deadline_at, expiry_policy, horizon_seconds, close_requested, opened_at
                )
                VALUES (?, ?, ?, 'LONG', 0.1, 70000, 350, 350, 'OPEN', 1, 1, ?, 'CLOSE_AT_DEADLINE', 3600, false, ?)
                """, posId, sessionId, BTC_ID, Timestamp.from(openedAt.plusSeconds(3600)), Timestamp.from(openedAt));

        int closed = reportingService.closeOpenPositions(sessionId, null, posId, openedAt.plusSeconds(30));
        assertEquals(1, closed);

        var afterClose = reportingService.snapshotForOwner(owner, sessionId);
        assertEquals(1, afterClose.trades().size());
        var trade = afterClose.trades().getFirst();
        assertEquals(posId, trade.positionId());
        assertEquals("CLOSED", trade.status());
        assertEquals("BTCUSDT", trade.symbol());
        assertNotNull(trade.closedAt());
        assertTrue(new BigDecimal(trade.totalFees()).signum() > 0);
    }
}
