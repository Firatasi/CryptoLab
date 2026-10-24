package com.crypto.simulationservice;

import com.crypto.simulationservice.application.service.CreateSession;
import com.crypto.simulationservice.infrastructure.messaging.MarketIngress;
import com.crypto.simulationservice.infrastructure.messaging.MarketListener;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MarketIngressIT {

    @Autowired
    MarketIngress ingress;

    @Autowired
    MarketListener listener;

    @Autowired
    CreateSession createSession;

    @Autowired
    JdbcTemplate db;

    @Test
    void duplicateEvent_createsSingleMarketInputAndFanOutToActiveSessionsOnly() throws Exception {
        UUID activeSession = createSession.execute(UUID.randomUUID(), new BigDecimal("10000"), UUID.randomUUID(), UUID.randomUUID());
        UUID closedSession = createSession.execute(UUID.randomUUID(), new BigDecimal("10000"), UUID.randomUUID(), UUID.randomUUID());
        UUID instrumentId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        db.update("""
                INSERT INTO positions(id, session_id, instrument_id, side, q, entry_price, original_margin, initial_margin_remaining, state, generation, deadline_at, expiry_policy, horizon_seconds, opened_at)
                VALUES (?, ?, ?, 'LONG', 1, 50000, 5000, 5000, 'OPEN', 1, now() + interval '1 hour', 'CLOSE_AT_DEADLINE', 3600, now())
                """, UUID.randomUUID(), activeSession, instrumentId);

        db.update("""
                INSERT INTO positions(id, session_id, instrument_id, side, q, entry_price, original_margin, initial_margin_remaining, state, generation, deadline_at, expiry_policy, horizon_seconds, opened_at, closed_at)
                VALUES (?, ?, ?, 'LONG', 1, 50000, 5000, 5000, 'CLOSED', 1, now() + interval '1 hour', 'CLOSE_AT_DEADLINE', 3600, now(), now())
                """, UUID.randomUUID(), closedSession, instrumentId);

        String payload = """
                {
                  "eventId": "%s",
                  "payload": {
                    "instrumentId": "%s",
                    "markPrice": "50100.00"
                  }
                }
                """.formatted(eventId, instrumentId);

        Acknowledgment ack = Mockito.mock(Acknowledgment.class);

        // Aynı olayı iki kez tüket: tek DB etkisi olmalı
        listener.consume(payload, ack);
        listener.consume(payload, ack);

        verify(ack, times(2)).acknowledge();

        Integer inputCount = db.queryForObject("SELECT count(*) FROM market_inputs WHERE id = ?", Integer.class, eventId);
        assertEquals(1, inputCount);

        Integer activeFanout = db.queryForObject(
                "SELECT count(*) FROM pending_engine_inputs WHERE session_id = ? AND input_id = ?",
                Integer.class, activeSession, eventId);
        assertEquals(1, activeFanout);

        Integer closedFanout = db.queryForObject(
                "SELECT count(*) FROM pending_engine_inputs WHERE session_id = ? AND input_id = ?",
                Integer.class, closedSession, eventId);
        assertEquals(0, closedFanout);
    }

    @Test
    void malformedEvent_doesNotCommitOrAcknowledge() {
        Integer beforeCount = db.queryForObject("SELECT count(*) FROM market_inputs", Integer.class);
        Acknowledgment ack = Mockito.mock(Acknowledgment.class);
        assertThrows(Exception.class, () -> listener.consume("{\"invalid\":true}", ack));
        verify(ack, never()).acknowledge();

        Integer afterCount = db.queryForObject("SELECT count(*) FROM market_inputs", Integer.class);
        assertEquals(beforeCount, afterCount);
    }
}
