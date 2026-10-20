package com.crypto.simulationservice;

import com.crypto.financecore.finance.Decimal;
import com.crypto.financecore.finance.Finance;
import com.crypto.financecore.finance.MaintenanceTier;
import com.crypto.financecore.finance.PositionSide;
import com.crypto.simulationservice.application.repository.LedgerRepository;
import com.crypto.simulationservice.application.repository.SessionRepository;
import com.crypto.simulationservice.application.service.CreateSession;
import com.crypto.simulationservice.application.service.DeadlineScheduler;
import com.crypto.simulationservice.domain.model.FundingBoundary;
import com.crypto.simulationservice.domain.model.Journal;
import com.crypto.simulationservice.domain.service.ExitDecision;
import com.crypto.simulationservice.domain.service.FillAccounting;
import com.crypto.simulationservice.domain.service.RiskDecision;
import com.crypto.simulationservice.domain.service.RiskPolicyEvaluator;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RiskAndDeadlineIT {

    @Autowired
    CreateSession createSession;

    @Autowired
    DeadlineScheduler deadlineScheduler;

    @Autowired
    SessionRepository sessions;

    @Autowired
    LedgerRepository ledger;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcTemplate db;

    @Autowired
    DataSource dataSource;

    private static final List<MaintenanceTier> TIERS = List.of(
            new MaintenanceTier(Decimal.parse("0"), Decimal.parse("1000000"), Decimal.parse("0.005"), Decimal.parse("0"))
    );

    @Test
    void migrateMainSimulationDbSchemaForDbeaverInspection() {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .defaultSchema("simulation_db")
                .schemas("simulation_db")
                .createSchemas(true)
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();

        Integer count = db.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'simulation_db' AND table_name IN ('funding_boundaries','funding_settlements','due_actions','daily_risk','halt_reasons')",
                Integer.class);
        assertEquals(5, count);
    }

    @Test
    void riskDecision_prioritizesLiquidationOverStopLossOnSameStep() {
        var action = RiskDecision.onMark(
                PositionSide.LONG,
                Decimal.parse("1"),
                Decimal.parse("50000"),
                Decimal.parse("45000"),
                Decimal.parse("5000"),
                Decimal.parse("46000"),
                Decimal.parse("55000"),
                TIERS
        );
        assertEquals(RiskDecision.Action.LIQUIDATE, action);

        var stopAction = RiskDecision.onMark(
                PositionSide.LONG,
                Decimal.parse("1"),
                Decimal.parse("50000"),
                Decimal.parse("47500"),
                Decimal.parse("5000"),
                Decimal.parse("48000"),
                Decimal.parse("55000"),
                TIERS
        );
        assertEquals(RiskDecision.Action.STOP_LOSS, stopAction);
    }

    @Test
    void riskPolicyEvaluator_computesMinSafeSizeAndDetectsDailyHalt() {
        var limits = new RiskPolicyEvaluator.Limits(
                Decimal.parse("20"),
                3,
                Decimal.parse("50000"),
                Decimal.parse("100000"),
                Decimal.parse("2000"),
                Decimal.parse("0.02"),
                Decimal.parse("0.05")
        );

        BigDecimal q = RiskPolicyEvaluator.size(
                Decimal.parse("50000"),
                Decimal.parse("49000"),
                Decimal.parse("10"),
                Decimal.parse("10000"),
                Decimal.parse("10000"),
                Decimal.parse("0"),
                Decimal.parse("2000"),
                Decimal.parse("0.0005"),
                Decimal.parse("0"),
                Decimal.parse("0.001"),
                limits
        );
        assertEquals(0, Decimal.parse("0.2").compareTo(q));

        assertTrue(RiskPolicyEvaluator.dailyHalt(
                Decimal.parse("10000"),
                Decimal.parse("9400"),
                Decimal.parse("0"),
                Decimal.parse("0.05")
        ));
        assertFalse(RiskPolicyEvaluator.dailyHalt(
                Decimal.parse("10000"),
                Decimal.parse("9600"),
                Decimal.parse("0"),
                Decimal.parse("0.05")
        ));
    }

    @Test
    void fundingBoundary_checksEligibilityAndUniqueSettlementConstraint() {
        Instant boundary = Instant.parse("2026-02-01T16:00:00Z");
        assertFalse(FundingBoundary.eligible(boundary, null, boundary));
        assertTrue(FundingBoundary.eligible(boundary.minusSeconds(60), boundary, boundary));

        var openAlloc = FundingBoundary.allocate(Decimal.parse("-150"), Decimal.parse("100"), false);
        assertEquals(0, Decimal.parse("-100").compareTo(openAlloc.availableDelta()));
        assertEquals(0, Decimal.parse("-50").compareTo(openAlloc.isolatedDelta()));
        assertEquals(0, Decimal.parse("150").compareTo(openAlloc.poolDelta()));

        var closedAlloc = FundingBoundary.allocate(Decimal.parse("-150"), Decimal.parse("100"), true);
        assertEquals(0, Decimal.parse("-100").compareTo(closedAlloc.availableDelta()));
        assertEquals(0, BigDecimal.ZERO.compareTo(closedAlloc.isolatedDelta()));

        UUID sessionId = createSession.execute(UUID.randomUUID(), new BigDecimal("10000"), UUID.randomUUID(), UUID.randomUUID());
        UUID positionId = UUID.randomUUID();
        UUID instrumentId = UUID.randomUUID();
        Instant now = Instant.now();
        db.update("""
                INSERT INTO positions(id, session_id, instrument_id, side, q, entry_price, original_margin, initial_margin_remaining, state, generation, deadline_at, expiry_policy, horizon_seconds, opened_at)
                VALUES (?, ?, ?, 'LONG', 1, 50000, 5000, 5000, 'OPEN', 1, ?, 'CLOSE_AT_DEADLINE', 3600, ?)
                """, positionId, sessionId, instrumentId, Timestamp.from(now.plusSeconds(3600)), Timestamp.from(now));

        db.update("INSERT INTO funding_boundaries(position_id, funding_time, q_at_boundary, side, instrument_id) VALUES (?, ?, 1, 'LONG', ?)",
                positionId, Timestamp.from(boundary), instrumentId);
        assertThrows(DuplicateKeyException.class, ()
                -> db.update("INSERT INTO funding_boundaries(position_id, funding_time, q_at_boundary, side, instrument_id) VALUES (?, ?, 1, 'LONG', ?)",
                        positionId, Timestamp.from(boundary), instrumentId));
    }

    @Test
    void exitDecision_holdsWhenQuoteStaleAndForcesFullCloseOnNegativePartialResidual() {
        var stale = ExitDecision.decide(
                PositionSide.LONG, Decimal.parse("1"), Decimal.parse("0.5"),
                Decimal.parse("50000"), Decimal.parse("1000"),
                Decimal.parse("49900"), Decimal.parse("50000"),
                Decimal.parse("10"), Decimal.parse("10"),
                Decimal.parse("0.1"), Decimal.parse("0"), Decimal.parse("1"),
                Decimal.parse("0.0005"), Decimal.parse("0.005"),
                false, false
        );
        assertEquals("EXIT_PENDING_DATA", stale.status());
        assertNull(stale.price());

        var forced = ExitDecision.decide(
                PositionSide.LONG, Decimal.parse("1"), Decimal.parse("0.5"),
                Decimal.parse("50000"), Decimal.parse("500"),
                Decimal.parse("49000"), Decimal.parse("49010"),
                Decimal.parse("10"), Decimal.parse("10"),
                Decimal.parse("0.1"), Decimal.parse("0"), Decimal.parse("1"),
                Decimal.parse("0.0005"), Decimal.parse("0.005"),
                false, true
        );
        assertEquals("FILLED", forced.status());
        assertEquals("INSOLVENT_EXIT", forced.reason());
        assertEquals(0, Decimal.parse("1").compareTo(forced.quantity()));
        assertTrue(forced.liquidityApproximation());
    }

    @Test
    void deadlineScheduler_schedulesIdempotentlyAndClaimsOnlyOnce() {
        UUID sessionId = createSession.execute(UUID.randomUUID(), new BigDecimal("10000"), UUID.randomUUID(), UUID.randomUUID());
        UUID positionId = UUID.randomUUID();
        UUID instrumentId = UUID.randomUUID();

        Instant openedAt = Instant.now().minusSeconds(120);
        db.update("""
                INSERT INTO positions(id, session_id, instrument_id, side, q, entry_price, original_margin, initial_margin_remaining, state, generation, deadline_at, expiry_policy, horizon_seconds, opened_at)
                VALUES (?, ?, ?, 'LONG', 1, 50000, 5000, 5000, 'OPEN', 1, ?, 'CLOSE_AT_DEADLINE', 60, ?)
                """, positionId, sessionId, instrumentId, Timestamp.from(openedAt.plusSeconds(60)), Timestamp.from(openedAt));

        deadlineScheduler.schedule(positionId, sessionId, 1L, 60, "CLOSE_AT_DEADLINE");
        deadlineScheduler.schedule(positionId, sessionId, 1L, 60, "CLOSE_AT_DEADLINE");

        assertTrue(deadlineScheduler.claim(positionId, 1L, "CLOSE_AT_DEADLINE"));
        assertFalse(deadlineScheduler.claim(positionId, 1L, "CLOSE_AT_DEADLINE"));
    }

    @Test
    void concurrentManualStopAndLiquidationRace_closesPositionOnlyOnceAndPostsSingleJournal() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID sessionId = createSession.execute(ownerId, new BigDecimal("10000"), UUID.randomUUID(), UUID.randomUUID());
        UUID positionId = UUID.randomUUID();
        UUID instrumentId = UUID.randomUUID();
        Instant now = Instant.now();

        UUID availableId = db.queryForObject(
                "SELECT id FROM wallet_accounts WHERE session_id = ? AND account_key = 'AVAILABLE'",
                UUID.class, sessionId);
        UUID isolatedId = UUID.randomUUID();
        UUID feesId = UUID.randomUUID();
        UUID pnlPoolId = UUID.randomUUID();
        UUID insuranceId = UUID.randomUUID();

        db.update("""
                INSERT INTO wallet_accounts(id, session_id, account_key, kind)
                VALUES
                    (?, ?, 'ISOLATED:POS1', 'ISOLATED'),
                    (?, ?, 'SIM_FEES', 'SYSTEM'),
                    (?, ?, 'SIM_PNL_POOL', 'SYSTEM'),
                    (?, ?, 'SIM_INSURANCE', 'SYSTEM')
                """,
                isolatedId, sessionId,
                feesId, sessionId,
                pnlPoolId, sessionId,
                insuranceId, sessionId);

        tx.executeWithoutResult(status -> {
            sessions.lock(sessionId, ownerId);
            var openCalc = Finance.open(
                    Decimal.parse("1000"), Decimal.parse("10"), Decimal.parse("50000"),
                    Decimal.parse("0.001"), Decimal.parse("0.0005"), Decimal.parse("10000"));
            var openPosting = FillAccounting.opening(availableId, isolatedId, feesId, openCalc);
            ledger.post(new Journal(
                    UUID.randomUUID(), sessionId, "open:" + positionId, "hash-open-" + positionId,
                    "POSITION_OPEN", openPosting
            ), now, now);
            db.update("""
                    INSERT INTO positions(id, session_id, instrument_id, side, q, entry_price, original_margin, initial_margin_remaining, state, generation, deadline_at, expiry_policy, horizon_seconds, opened_at)
                    VALUES (?, ?, ?, 'LONG', 0.2, 50000, 1000, 1000, 'OPEN', 1, ?, 'CLOSE_AT_DEADLINE', 3600, ?)
                    """, positionId, sessionId, instrumentId, Timestamp.from(now.plusSeconds(3600)), Timestamp.from(now));
        });

        CountDownLatch ready = new CountDownLatch(3);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger closedWinners = new AtomicInteger(0);
        String[] reasons = {"USER_CLOSE", "STOP_LOSS", "LIQUIDATION"};

        try (var pool = Executors.newFixedThreadPool(3)) {
            for (String reason : reasons) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await(5, TimeUnit.SECONDS);
                        Boolean won = tx.execute(status -> {
                            sessions.lock(sessionId, ownerId);
                            String currentState = db.queryForObject(
                                    "SELECT state FROM positions WHERE id = ? FOR UPDATE",
                                    String.class, positionId);
                            if (!"OPEN".equals(currentState) && !"LIQUIDATING".equals(currentState)) {
                                return false;
                            }
                            var closeRes = Finance.close(
                                    PositionSide.LONG, Decimal.parse("0.2"), Decimal.parse("0.2"),
                                    Decimal.parse("50000"), Decimal.parse("50500"),
                                    Decimal.parse("1000"), Decimal.parse("0.0005")
                            );
                            var postings = FillAccounting.closing(availableId, isolatedId, pnlPoolId, feesId, insuranceId, closeRes);
                            ledger.post(new Journal(
                                    UUID.randomUUID(), sessionId, "close:" + positionId, "hash-close-" + positionId,
                                    reason, postings
                            ), now, now);
                            db.update("UPDATE positions SET q = 0, state = 'CLOSED', closed_at = now() WHERE id = ?", positionId);
                            return true;
                        });
                        if (Boolean.TRUE.equals(won)) {
                            closedWinners.incrementAndGet();
                        }
                    } catch (Exception ignored) {
                    }
                });
            }
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        // Üç yarışan çıkıştan (manuel, stop, likidasyon) yalnız biri pozisyonu kapatabilmeli
        assertEquals(1, closedWinners.get());
        String finalState = db.queryForObject("SELECT state FROM positions WHERE id = ?", String.class, positionId);
        assertEquals("CLOSED", finalState);
        Integer closeJournals = db.queryForObject(
                "SELECT count(*) FROM journal_transactions WHERE session_id = ? AND business_key = ?",
                Integer.class, sessionId, "close:" + positionId);
        assertEquals(1, closeJournals);
    }
}
