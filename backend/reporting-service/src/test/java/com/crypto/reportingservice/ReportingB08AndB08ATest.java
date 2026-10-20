package com.crypto.reportingservice;

import com.crypto.reportingservice.domain.service.ReportMetrics;
import com.crypto.reportingservice.infrastructure.export.CsvCell;
import com.crypto.reportingservice.infrastructure.repository.AccountSummaryQueryRepository;
import com.crypto.reportingservice.infrastructure.repository.LedgerProjection;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class ReportingB08AndB08ATest {

    @Autowired
    LedgerProjection projection;

    @Autowired
    AccountSummaryQueryRepository accountQueries;

    @Autowired
    JdbcTemplate db;

    @Autowired
    DataSource dataSource;

    @Test
    void migrateMainReportingDbSchemaForDbeaverInspection() {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .defaultSchema("reporting_db")
                .schemas("reporting_db")
                .createSchemas(true)
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();

        Integer count = db.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'reporting_db' AND table_name IN ('session_owners','ledger_projection','trade_summaries')",
                Integer.class);
        assertEquals(3, count);
    }

    @Test
    void ledgerProjection_isIdempotentRejectsSequenceGapAndIgnoresPositionClosedForBalance() throws Exception {
        UUID sessionId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID availableAcc = UUID.randomUUID();
        UUID capitalAcc = UUID.randomUUID();
        UUID journalId = UUID.randomUUID();
        UUID event1 = UUID.randomUUID();
        UUID event2 = UUID.randomUUID();

        db.update("INSERT INTO session_owners(session_id, owner_id, mode, currency) VALUES (?, ?, 'LIVE_PAPER', 'USDT')",
                sessionId, ownerId);
        db.update("INSERT INTO account_metadata(account_id, session_id, account_key, kind) VALUES (?, ?, 'AVAILABLE', 'AVAILABLE'), (?, ?, 'SIM_CAPITAL', 'SYSTEM')",
                availableAcc, sessionId, capitalAcc, sessionId);

        String ledgerEvent = """
                {
                  "eventId": "%s",
                  "sessionId": "%s",
                  "engineSequence": 1,
                  "eventType": "LedgerTransactionPosted",
                  "recordedAt": "2026-02-01T12:00:00Z",
                  "payload": {
                    "journalTransactionId": "%s",
                    "effectiveAt": "2026-02-01T12:00:00Z",
                    "postings": [
                      {"accountId": "%s", "amount": "10000.00"},
                      {"accountId": "%s", "amount": "-10000.00"}
                    ]
                  }
                }
                """.formatted(event1, sessionId, journalId, availableAcc, capitalAcc);

        // Aynı ledger event iki kez -> bakiye tek kez artmalı
        projection.accept(ledgerEvent);
        projection.accept(ledgerEvent);

        BigDecimal balance = db.queryForObject(
                "SELECT balance FROM account_projection WHERE account_id = ?",
                BigDecimal.class, availableAcc);
        assertEquals(0, new BigDecimal("10000").compareTo(balance));

        // PositionClosed para toplamını ikinci kez artırmaz, yalnız checkpoint sequence ilerletir
        String posClosedEvent = """
                {
                  "eventId": "%s",
                  "sessionId": "%s",
                  "engineSequence": 2,
                  "eventType": "PositionClosed",
                  "recordedAt": "2026-02-01T12:05:00Z",
                  "payload": {}
                }
                """.formatted(event2, sessionId);
        projection.accept(posClosedEvent);

        BigDecimal balanceAfterClose = db.queryForObject(
                "SELECT balance FROM account_projection WHERE account_id = ?",
                BigDecimal.class, availableAcc);
        assertEquals(0, new BigDecimal("10000").compareTo(balanceAfterClose));

        // Sequence 4 (3 atlandı) -> PROJECTION_SEQUENCE_GAP
        String gapEvent = """
                {
                  "eventId": "%s",
                  "sessionId": "%s",
                  "engineSequence": 4,
                  "eventType": "PositionClosed",
                  "recordedAt": "2026-02-01T12:10:00Z",
                  "payload": {}
                }
                """.formatted(UUID.randomUUID(), sessionId);
        assertThrows(IllegalStateException.class, () -> projection.accept(gapEvent));
    }

    @Test
    void reportMetrics_andCsvCellFormulaInjectionPrevention() {
        var trades = List.of(
                new ReportMetrics.Trade(new BigDecimal("100"), new BigDecimal("5"), new BigDecimal("-1"), BigDecimal.ZERO, BigDecimal.ZERO),
                new ReportMetrics.Trade(new BigDecimal("-40"), new BigDecimal("5"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("10"))
        );
        assertEquals(0, new BigDecimal("0.5").compareTo(ReportMetrics.winRate(trades)));
        assertNotNull(ReportMetrics.profitFactor(trades));
        assertEquals(0, new BigDecimal("0.2").compareTo(
                ReportMetrics.maxDrawdown(List.of(new BigDecimal("10000"), new BigDecimal("11000"), new BigDecimal("8800")))));

        // CSV formül enjeksiyonu önlemi
        assertEquals("\"'=HYPERLINK(\"\"http://evil\"\")\"", CsvCell.encode("=HYPERLINK(\"http://evil\")"));
    }

    @Test
    void accountSummaryAndPagination_enforcesOwnerScopeFormulaAndSignedCursor() {
        UUID ownerA = UUID.randomUUID();
        UUID ownerB = UUID.randomUUID();
        UUID sessionA = UUID.randomUUID();
        UUID sessionReplay = UUID.randomUUID();
        UUID sessionB = UUID.randomUUID();
        Instant now = Instant.parse("2026-02-01T15:00:00Z");

        db.update("INSERT INTO session_owners(session_id, owner_id, mode, currency) VALUES (?, ?, 'LIVE_PAPER', 'USDT')", sessionA, ownerA);
        db.update("INSERT INTO session_owners(session_id, owner_id, mode, currency) VALUES (?, ?, 'HISTORICAL_REPLAY', 'USDT')", sessionReplay, ownerA);
        db.update("INSERT INTO session_owners(session_id, owner_id, mode, currency) VALUES (?, ?, 'LIVE_PAPER', 'USDT')", sessionB, ownerB);

        db.update("INSERT INTO projection_checkpoints(session_id, last_sequence, as_of) VALUES (?, 5, ?), (?, 5, ?), (?, 5, ?)",
                sessionA, Timestamp.from(now), sessionReplay, Timestamp.from(now), sessionB, Timestamp.from(now));

        // B08A örneği: realized=100, fees=10.05, funding=-1, liquidationFees=0, insurance=5 -> netRealized=88.95
        // Açık PnL=-20 -> totalNet=68.95 (insurance ayrı)
        UUID pos1 = UUID.randomUUID();
        UUID pos2 = UUID.randomUUID();
        db.update("""
                INSERT INTO trade_summaries(position_id, session_id, instrument_id, symbol, side, status, opened_at, closed_at, gross, fees, funding, liquidation_fees, insurance)
                VALUES (?, ?, ?, 'BTCUSDT', 'LONG', 'CLOSED', ?, ?, 100, 8.05, 0, 0, 5),
                       (?, ?, ?, 'ETHUSDT', 'SHORT', 'OPEN', ?, null, 0, 2.00, -1, 0, 0)
                """,
                pos1, sessionA, UUID.randomUUID(), Timestamp.from(now.minusSeconds(120)), Timestamp.from(now.minusSeconds(60)),
                pos2, sessionA, UUID.randomUUID(), Timestamp.from(now.minusSeconds(30)));

        db.update("INSERT INTO equity_samples(session_id, engine_sequence, as_of, equity, unrealized_pnl, quality) VALUES (?, 5, ?, 10068.95, -20, 'FRESH')",
                sessionA, Timestamp.from(now));

        // Replay session özete dahil edilmemeli
        db.update("""
                INSERT INTO trade_summaries(position_id, session_id, instrument_id, symbol, side, status, opened_at, closed_at, gross, fees, funding, liquidation_fees, insurance)
                VALUES (?, ?, ?, 'BTCUSDT', 'LONG', 'CLOSED', ?, ?, 9999, 0, 0, 0, 0)
                """, UUID.randomUUID(), sessionReplay, UUID.randomUUID(), Timestamp.from(now), Timestamp.from(now));

        var summary = accountQueries.querySummary(ownerA, null);
        assertEquals("READY", summary.coverage());
        assertEquals("100", summary.realizedPnl());
        assertEquals("10.05", summary.totalFees());
        assertEquals("-1", summary.signedFunding());
        assertEquals("0", summary.liquidationFees());
        assertEquals("88.95", summary.netRealizedPnl());
        assertEquals("-20", summary.unrealizedPnl());
        assertEquals("68.95", summary.totalNetPnl());
        assertEquals("5", summary.insuranceAdjustment());
        assertEquals(1L, summary.closedTradeCount()); // Yalnız CLOSED/LIQUIDATED sayılır

        // Kullanıcı A, B'nin sessionId'sini sorgularsa 404
        var ex404 = assertThrows(ResponseStatusException.class, () -> accountQueries.querySummary(ownerA, sessionB));
        assertEquals(404, ex404.getStatusCode().value());

        // Pagination (limit=1 -> nextCursor üretilir; ikinci sayfada kalan 1 kayıt gelir; başka kullanıcı aynı cursor'u kullanırsa 400)
        var page1 = accountQueries.queryTradeHistory(ownerA, null, null, 1);
        assertEquals(1, page1.items().size());
        assertNotNull(page1.nextCursor());

        var page2 = accountQueries.queryTradeHistory(ownerA, null, page1.nextCursor(), 1);
        assertEquals(1, page2.items().size());
        assertNull(page2.nextCursor());

        var ex400 = assertThrows(ResponseStatusException.class,
                () -> accountQueries.queryTradeHistory(ownerB, null, page1.nextCursor(), 1));
        assertEquals(400, ex400.getStatusCode().value());
    }
}

