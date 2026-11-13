package com.crypto.reportingservice;

import com.crypto.reportingservice.infrastructure.client.ReportingSnapshotWriter;
import com.crypto.reportingservice.infrastructure.client.ReportingSnapshotWriter.SessionSnapshot;
import com.crypto.reportingservice.infrastructure.client.ReportingSnapshotWriter.SnapshotPayload;
import com.crypto.reportingservice.infrastructure.client.ReportingSnapshotWriter.TradeSnapshot;
import com.crypto.reportingservice.infrastructure.repository.AccountSummaryQueryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class ReportingSnapshotSyncTest {

    @Autowired
    ReportingSnapshotWriter writer;

    @Autowired
    AccountSummaryQueryRepository accountQueries;

    @Test
    void syncedSessionWithoutTradesReturnsReadyEmptyHistoryAndStillIsolatesOwners() {
        UUID owner = UUID.randomUUID();
        UUID otherOwner = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-13T01:00:00Z");

        writer.persistSnapshot(owner, new SnapshotPayload(
                owner,
                List.of(new SessionSnapshot(
                        sessionId, owner, "LIVE_PAPER", "USDT", 1L,
                        new BigDecimal("10000"), BigDecimal.ZERO, now
                )),
                List.of(),
                List.of()
        ));

        var history = accountQueries.queryTradeHistory(owner, sessionId, null, 25);
        assertEquals("READY", history.coverage());
        assertEquals(sessionId, history.sessionId());
        assertTrue(history.items().isEmpty());

        var summary = accountQueries.querySummary(owner, sessionId);
        assertEquals("READY", summary.coverage());
        assertEquals(0L, summary.closedTradeCount());
        assertEquals("0", summary.realizedPnl());

        var ex404 = assertThrows(ResponseStatusException.class,
                () -> accountQueries.queryTradeHistory(otherOwner, sessionId, null, 25));
        assertEquals(404, ex404.getStatusCode().value());
    }

    @Test
    void syncedTradesAndSoftDeleteAreProjectedIdempotently() {
        UUID owner = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID posClosed = UUID.randomUUID();
        UUID posOpen = UUID.randomUUID();
        UUID instBtc = UUID.fromString("11111111-1111-4111-8111-111111111111");
        Instant t1 = Instant.parse("2026-11-13T01:10:00Z");
        Instant t2 = Instant.parse("2026-11-13T01:15:00Z");

        var payload = new SnapshotPayload(
                owner,
                List.of(new SessionSnapshot(
                        sessionId, owner, "LIVE_PAPER", "USDT", 3L,
                        new BigDecimal("10120"), new BigDecimal("15.5"), t2
                )),
                List.of(),
                List.of(
                        new TradeSnapshot(
                                posClosed, sessionId, instBtc, "BTCUSDT", "LONG", "CLOSED",
                                t1, t2,
                                new BigDecimal("120"), new BigDecimal("8"),
                                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO
                        ),
                        new TradeSnapshot(
                                posOpen, sessionId, instBtc, "BTCUSDT", "SHORT", "OPEN",
                                t2, null,
                                BigDecimal.ZERO, new BigDecimal("2"),
                                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO
                        )
                )
        );

        writer.persistSnapshot(owner, payload);
        writer.persistSnapshot(owner, payload);

        var history = accountQueries.queryTradeHistory(owner, sessionId, null, 25);
        assertEquals(2, history.items().size());
        assertEquals("112", history.items().stream()
                .filter(t -> posClosed.equals(t.positionId()))
                .findFirst().orElseThrow().netRealizedPnl());

        var summary = accountQueries.querySummary(owner, sessionId);
        assertEquals(1L, summary.closedTradeCount());
        assertEquals("120", summary.realizedPnl());
        assertEquals("10", summary.totalFees());
        assertEquals("110", summary.netRealizedPnl());
        assertEquals("15.5", summary.unrealizedPnl());
        assertEquals("125.5", summary.totalNetPnl());

        writer.persistSnapshot(owner, new SnapshotPayload(
                owner, List.of(), List.of(sessionId), List.of()
        ));
        var deleted404 = assertThrows(ResponseStatusException.class,
                () -> accountQueries.queryTradeHistory(owner, sessionId, null, 25));
        assertEquals(404, deleted404.getStatusCode().value());
    }
}
