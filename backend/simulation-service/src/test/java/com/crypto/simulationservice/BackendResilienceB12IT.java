package com.crypto.simulationservice;

import com.crypto.financecore.finance.Decimal;
import com.crypto.financecore.finance.Finance;
import com.crypto.financecore.finance.PositionSide;
import com.crypto.simulationservice.application.command.SubmitOrderCommand;
import com.crypto.simulationservice.application.repository.LedgerRepository;
import com.crypto.simulationservice.application.service.CreateSession;
import com.crypto.simulationservice.application.service.PlanHash;
import com.crypto.simulationservice.application.service.SubmitOrder;
import com.crypto.simulationservice.domain.exception.BusinessException;
import com.crypto.simulationservice.domain.model.ExecutionPlan;
import com.crypto.simulationservice.domain.model.FundingBoundary;
import com.crypto.simulationservice.domain.model.Journal;
import com.crypto.simulationservice.domain.service.ExitDecision;
import com.crypto.simulationservice.domain.service.FillAccounting;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class BackendResilienceB12IT {

    @Autowired
    private CreateSession createSession;

    @Autowired
    private SubmitOrder submitOrder;

    @Autowired
    private LedgerRepository ledger;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private Clock clock;

    @Test
    void b12_3_twentyParallelDuplicateSubmitsWithCountDownLatchProduceSingleCommandAndSingleApproval() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID ruleId = UUID.randomUUID();
        UUID riskId = UUID.randomUUID();
        UUID sessionId = createSession.execute(ownerId, new BigDecimal("10000"), ruleId, riskId);
        UUID instrumentId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        int revision = 1;
        Instant now = clock.instant();
        Instant validUntil = now.plusSeconds(60);

        jdbc.update("""
                INSERT INTO instrument_generations(session_id, instrument_id, generation)
                VALUES (?, ?, 1)
                """, sessionId, instrumentId);

        ExecutionPlan plan = new ExecutionPlan(
                planId, revision, null, sessionId, instrumentId, 1L,
                "MANUAL", null, 1L, "LONG", "MARKET",
                Decimal.parse("0.2"), Decimal.parse("50000"), null,
                Decimal.parse("1000"), Decimal.parse("5"), Decimal.parse("10000"), Decimal.parse("10"),
                Decimal.parse("48000"), Decimal.parse("55000"), 3600, "CLOSE_AT_DEADLINE",
                Decimal.parse("49900"), Decimal.parse("50100"), Decimal.parse("5"), Decimal.parse("0"),
                ruleId, 1L, now, validUntil, Decimal.parse("45180.722891"), List.of()
        );

        String canonicalJson = new String(PlanHash.canonical(plan), StandardCharsets.UTF_8);
        String hash = PlanHash.hash(plan);

        jdbc.update("""
                INSERT INTO execution_plans(
                    plan_id, revision, session_id, instrument_id,
                    canonical_json, hash, position_generation, valid_until, state
                )
                VALUES (?, ?, ?, ?, ?::jsonb, ?, 1, ?, 'PREVIEWED')
                """,
                planId, revision, sessionId, instrumentId,
                canonicalJson, hash, Timestamp.from(validUntil));

        String sharedKey = "idem-20-race-" + UUID.randomUUID();
        int threadCount = 20;
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        Set<UUID> returnedCommandIds = ConcurrentHashMap.newKeySet();

        try (var pool = Executors.newFixedThreadPool(threadCount)) {
            for (int i = 0; i < threadCount; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await(10, TimeUnit.SECONDS);
                        SubmitOrder.Accepted res = submitOrder.execute(
                                ownerId, sharedKey, new SubmitOrderCommand(planId, revision, hash)
                        );
                        returnedCommandIds.add(res.commandId());
                    } catch (Exception ignored) {
                    }
                });
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));
        }

        // 20 paralel isteğin tamamı tek bir commandId üretmeli; DB'de yalnız 1 command ve 1 approval olmalı
        assertEquals(1, returnedCommandIds.size());
        Long commandCount = jdbc.queryForObject(
                "SELECT count(*) FROM commands WHERE session_id = ?",
                Long.class, sessionId
        );
        Long approvalCount = jdbc.queryForObject(
                "SELECT count(*) FROM approvals WHERE plan_id = ? AND revision = ?",
                Long.class, planId, revision
        );
        assertEquals(1L, commandCount);
        assertEquals(1L, approvalCount);

        // Farklı key ile aynı plan tekrar onaylanamaz
        BusinessException planReuse = assertThrows(
                BusinessException.class,
                () -> submitOrder.execute(
                        ownerId, "idem-different-key-" + UUID.randomUUID(),
                        new SubmitOrderCommand(planId, revision, hash)
                )
        );
        assertEquals(BusinessException.Code.PLAN_REVOKED, planReuse.code());

        // Cross-user erişim reddedilir (404 NOT_FOUND)
        BusinessException crossUser = assertThrows(
                BusinessException.class,
                () -> submitOrder.execute(
                        UUID.randomUUID(), "idem-cross-user",
                        new SubmitOrderCommand(planId, revision, hash)
                )
        );
        assertEquals(BusinessException.Code.NOT_FOUND, crossUser.code());
    }

    @Test
    void b12_2_and_b12_3_endToEndOpenFundingCloseRollbackStaleQuoteAndZeroLedgerImbalance() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID sessionId = createSession.execute(ownerId, new BigDecimal("10000"), UUID.randomUUID(), UUID.randomUUID());
        UUID positionId = UUID.randomUUID();
        UUID instrumentId = UUID.randomUUID();
        Instant now = Instant.parse("2026-03-01T15:59:00Z");
        Instant boundaryTime = Instant.parse("2026-03-01T16:00:00Z");

        UUID availableId = jdbc.queryForObject(
                "SELECT id FROM wallet_accounts WHERE session_id = ? AND account_key = 'AVAILABLE'",
                UUID.class, sessionId
        );
        UUID isolatedId = UUID.randomUUID();
        UUID feesId = UUID.randomUUID();
        UUID pnlPoolId = UUID.randomUUID();
        UUID insuranceId = UUID.randomUUID();

        jdbc.update("""
                INSERT INTO wallet_accounts(id, session_id, account_key, kind)
                VALUES
                    (?, ?, 'ISOLATED:POS_B12', 'ISOLATED'),
                    (?, ?, 'SIM_FEES', 'SYSTEM'),
                    (?, ?, 'SIM_PNL_POOL', 'SYSTEM'),
                    (?, ?, 'SIM_INSURANCE', 'SYSTEM')
                """,
                isolatedId, sessionId,
                feesId, sessionId,
                pnlPoolId, sessionId,
                insuranceId, sessionId
        );

        // 1. Open fill (F01/F10): E=50000, q=0.2, L=10 -> A=8995, B=1000
        var openCalc = Finance.open(
                Decimal.parse("1000"), Decimal.parse("10"), Decimal.parse("50000"),
                Decimal.parse("0.001"), Decimal.parse("0.0005"), Decimal.parse("10000")
        );
        tx.executeWithoutResult(status -> {
            ledger.post(new Journal(
                    UUID.randomUUID(), sessionId, "b12-open:" + positionId, "hash-open",
                    "OPEN_FILL", FillAccounting.opening(availableId, isolatedId, feesId, openCalc)
            ), now, now);
            jdbc.update("""
                    INSERT INTO positions(id, session_id, instrument_id, side, q, entry_price, original_margin, initial_margin_remaining, state, generation, deadline_at, expiry_policy, horizon_seconds, opened_at)
                    VALUES (?, ?, ?, 'LONG', 0.2, 50000, 1000, 1000, 'OPEN', 1, ?, 'CLOSE_AT_DEADLINE', 3600, ?)
                    """, positionId, sessionId, instrumentId, Timestamp.from(now.plusSeconds(3600)), Timestamp.from(now));
        });

        // 2. Duplicate funding yarışı (CountDownLatch ile 2 paralel event) -> 1 settlement, 1 journal
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger settledCount = new AtomicInteger(0);

        try (var pool = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 2; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await(5, TimeUnit.SECONDS);
                        tx.executeWithoutResult(status -> {
                            jdbc.update("""
                                    INSERT INTO funding_boundaries(position_id, funding_time, q_at_boundary, side, instrument_id)
                                    VALUES (?, ?, 0.2, 'LONG', ?)
                                    """, positionId, Timestamp.from(boundaryTime), instrumentId);
                            var fundingRes = Finance.funding(
                                    PositionSide.LONG,
                                    Decimal.parse("0.2"),
                                    Decimal.parse("50000"),
                                    Decimal.parse("0.0001"),
                                    Decimal.parse("8995"),
                                    Decimal.parse("1000")
                            );
                            BigDecimal signedFunding = fundingRes.signedFunding();
                            var alloc = FundingBoundary.allocate(signedFunding, Decimal.parse("8995"), false);
                            UUID txId = ledger.post(new Journal(
                                    UUID.randomUUID(), sessionId,
                                    "b12-funding:" + positionId + ":" + boundaryTime,
                                    "hash-funding",
                                    "FUNDING_SETTLEMENT",
                                    List.of(
                                            new Journal.Posting(availableId, alloc.availableDelta()),
                                            new Journal.Posting(pnlPoolId, alloc.poolDelta())
                                    )
                            ), boundaryTime, boundaryTime);
                            jdbc.update("""
                                    INSERT INTO funding_settlements(
                                        position_id, funding_time, q_at_boundary, rate, mark,
                                        amount, from_a, from_b, late, journal_id
                                    )
                                    VALUES (?, ?, 0.2, 0.0001, 50000, ?, ?, 0, false, ?)
                                    """, positionId, Timestamp.from(boundaryTime), signedFunding, alloc.availableDelta(), txId);
                            settledCount.incrementAndGet();
                        });
                    } catch (Exception ignored) {
                    }
                });
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals(1, settledCount.get());
        Long fundingSettlements = jdbc.queryForObject(
                "SELECT count(*) FROM funding_settlements WHERE position_id = ?",
                Long.class, positionId
        );
        assertEquals(1L, fundingSettlements);

        // 3. Stale quote geldiğinde sahte close üretilmez (EXIT_PENDING_DATA)
        var staleDecision = ExitDecision.decide(
                PositionSide.LONG, Decimal.parse("0.2"), Decimal.parse("0.2"),
                Decimal.parse("50000"), Decimal.parse("1000"),
                Decimal.parse("50490"), Decimal.parse("50500"),
                Decimal.parse("10"), Decimal.parse("10"),
                Decimal.parse("0.001"), Decimal.parse("0"), Decimal.parse("1"),
                Decimal.parse("0.0005"), Decimal.parse("0.005"),
                false, false
        );
        assertEquals("EXIT_PENDING_DATA", staleDecision.status());

        // 4. Fill rollback: ledger sonrası exception fırlatıldığında bakiye ve pozisyon yarım kalmaz
        assertThrows(RuntimeException.class, () -> tx.executeWithoutResult(status -> {
            var closeCalc = Finance.close(
                    PositionSide.LONG, Decimal.parse("0.2"), Decimal.parse("0.2"),
                    Decimal.parse("50000"), Decimal.parse("50500"), Decimal.parse("1000"), Decimal.parse("0.0005")
            );
            ledger.post(new Journal(
                    UUID.randomUUID(), sessionId, "b12-close-rollback:" + positionId, "hash-rollback",
                    "CLOSE_FILL", FillAccounting.closing(availableId, isolatedId, pnlPoolId, feesId, insuranceId, closeCalc)
            ), boundaryTime.plusSeconds(10), boundaryTime.plusSeconds(10));
            throw new RuntimeException("INJECTED_CRASH_AFTER_LEDGER");
        }));

        String stateAfterRollback = jdbc.queryForObject(
                "SELECT state FROM positions WHERE id = ?", String.class, positionId
        );
        assertEquals("OPEN", stateAfterRollback);

        // 5. Gerçek close (50500) -> A = 8995 - 1 (funding) + 1094.95 = 10088.95, B = 0
        var closeCalc = Finance.close(
                PositionSide.LONG, Decimal.parse("0.2"), Decimal.parse("0.2"),
                Decimal.parse("50000"), Decimal.parse("50500"), Decimal.parse("1000"), Decimal.parse("0.0005")
        );
        tx.executeWithoutResult(status -> {
            ledger.post(new Journal(
                    UUID.randomUUID(), sessionId, "b12-close:" + positionId, "hash-close",
                    "CLOSE_FILL", FillAccounting.closing(availableId, isolatedId, pnlPoolId, feesId, insuranceId, closeCalc)
            ), boundaryTime.plusSeconds(60), boundaryTime.plusSeconds(60));
            jdbc.update("UPDATE positions SET q = 0, state = 'CLOSED', closed_at = now() WHERE id = ?", positionId);
        });

        BigDecimal finalAvailable = jdbc.queryForObject(
                "SELECT balance FROM wallet_accounts WHERE id = ?", BigDecimal.class, availableId
        );
        BigDecimal finalIsolated = jdbc.queryForObject(
                "SELECT balance FROM wallet_accounts WHERE id = ?", BigDecimal.class, isolatedId
        );
        assertEquals(0, new BigDecimal("10088.95").compareTo(finalAvailable));
        assertEquals(0, BigDecimal.ZERO.compareTo(finalIsolated));

        // 6. Sıfır ledger imbalance ve tam rebuild mutabakatı
        BigDecimal totalSum = jdbc.queryForObject("""
                SELECT sum(p.amount)
                FROM postings p
                JOIN journal_transactions j ON j.id = p.transaction_id
                WHERE j.session_id = ?
                """, BigDecimal.class, sessionId);
        assertNotNull(totalSum);
        assertEquals(0, totalSum.signum());

        var mismatches = jdbc.queryForList("""
                SELECT a.id
                FROM wallet_accounts a
                LEFT JOIN postings p ON p.account_id = a.id
                WHERE a.session_id = ?
                GROUP BY a.id, a.balance
                HAVING a.balance <> coalesce(sum(p.amount), 0)
                """, sessionId);
        assertTrue(mismatches.isEmpty());
    }
}
