package com.crypto.simulationservice;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;

import com.crypto.financecore.finance.Decimal;
import com.crypto.financecore.finance.Finance;
import com.crypto.financecore.finance.PositionSide;
import com.crypto.simulationservice.api.controller.OrderController;
import com.crypto.simulationservice.api.dto.request.SubmitOrderRequest;
import com.crypto.simulationservice.api.dto.response.AcceptedOrderResponse;
import com.crypto.simulationservice.application.command.SubmitOrderCommand;
import com.crypto.simulationservice.application.repository.LedgerRepository;
import com.crypto.simulationservice.application.service.CreateSession;
import com.crypto.simulationservice.application.service.PlanHash;
import com.crypto.simulationservice.application.service.SubmitOrder;
import com.crypto.simulationservice.domain.exception.BusinessException;
import com.crypto.simulationservice.domain.model.ExecutionPlan;
import com.crypto.simulationservice.domain.model.Journal;
import com.crypto.simulationservice.domain.service.FillAccounting;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OrderSubmissionIT {

    @Autowired
    private CreateSession createSession;

    @Autowired
    private SubmitOrder submitOrder;

    @Autowired
    private OrderController orderController;

    @Autowired
    private LedgerRepository ledger;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    private record SeededPlan(UUID ownerId, UUID sessionId, UUID instrumentId, UUID planId, int revision, String hash) {}

    private SeededPlan seedPlan(Instant validUntil, long generation, String planState, String sessionStatus) {
        UUID ownerId = UUID.randomUUID();
        UUID ruleId = UUID.randomUUID();
        UUID riskId = UUID.randomUUID();
        UUID sessionId = createSession.execute(ownerId, new BigDecimal("10000"), ruleId, riskId);
        UUID instrumentId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        int revision = 1;
        Instant now = clock.instant();

        if (!"RUNNING".equals(sessionStatus)) {
            jdbc.update("UPDATE simulation_sessions SET status = ? WHERE id = ?", sessionStatus, sessionId);
        }

        jdbc.update("""
                INSERT INTO instrument_generations(session_id, instrument_id, generation)
                VALUES (?, ?, ?)
                """, sessionId, instrumentId, generation);

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
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
                """,
                planId, revision, sessionId, instrumentId,
                canonicalJson, hash, 1L, Timestamp.from(validUntil), planState);

        return new SeededPlan(ownerId, sessionId, instrumentId, planId, revision, hash);
    }

    @Test
    void submitAcceptsPlanAndMaintainsIdempotencyAndSingleExecutionInvariants() {
        Instant now = clock.instant();
        SeededPlan seeded = seedPlan(now.plusSeconds(60), 1L, "PREVIEWED", "RUNNING");
        String key = "idem-" + UUID.randomUUID();

        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .subject(seeded.ownerId().toString())
                .build();

        ResponseEntity<AcceptedOrderResponse> firstResponse = orderController.submit(
                jwt, key, new SubmitOrderRequest(seeded.planId(), seeded.revision(), seeded.hash())
        );

        assertEquals(202, firstResponse.getStatusCode().value());
        AcceptedOrderResponse accepted = firstResponse.getBody();
        assertNotNull(accepted);
        assertEquals("ACCEPTED", accepted.status());
        assertEquals("/api/v1/sim/commands/" + accepted.commandId(), accepted.statusUrl());

        // Plan CONSUMED olmalı, approval ve command oluşmalı, fakat henüz position oluşmamalı.
        String planState = jdbc.queryForObject(
                "SELECT state FROM execution_plans WHERE plan_id = ? AND revision = ?",
                String.class, seeded.planId(), seeded.revision()
        );
        assertEquals("CONSUMED", planState);

        Long approvalCount = jdbc.queryForObject(
                "SELECT count(*) FROM approvals WHERE plan_id = ? AND revision = ? AND consumed_command_id = ?",
                Long.class, seeded.planId(), seeded.revision(), accepted.commandId()
        );
        assertEquals(1L, approvalCount);

        Long positionCount = jdbc.queryForObject(
                "SELECT count(*) FROM positions WHERE session_id = ?",
                Long.class, seeded.sessionId()
        );
        assertEquals(0L, positionCount);

        // Aynı key + aynı body tekrarında (plan artık CONSUMED olsa bile) aynı commandId dönmeli.
        SubmitOrder.Accepted repeated = submitOrder.execute(
                seeded.ownerId(), key, new SubmitOrderCommand(seeded.planId(), seeded.revision(), seeded.hash())
        );
        assertEquals(accepted.commandId(), repeated.commandId());

        // Aynı key + farklı body (veya farklı planHash) -> 409 IDEMPOTENCY_CONFLICT.
        String otherHash = "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        BusinessException conflict = assertThrows(
                BusinessException.class,
                () -> submitOrder.execute(
                        seeded.ownerId(), key, new SubmitOrderCommand(seeded.planId(), seeded.revision(), otherHash)
                )
        );
        assertEquals(BusinessException.Code.IDEMPOTENCY_CONFLICT, conflict.code());

        // Aynı plan + farklı key -> 409 PLAN_REVOKED (ikinci kez emir açmaz).
        BusinessException secondExecution = assertThrows(
                BusinessException.class,
                () -> submitOrder.execute(
                        seeded.ownerId(), "idem-second-" + UUID.randomUUID(),
                        new SubmitOrderCommand(seeded.planId(), seeded.revision(), seeded.hash())
                )
        );
        assertEquals(BusinessException.Code.PLAN_REVOKED, secondExecution.code());
    }

    @Test
    void submitRejectsHashMismatchExpiredRevokedHaltedAndOtherUsersPlan() {
        Instant now = clock.instant();

        // 1. planHash uyuşmazlığı -> REVALIDATION_REQUIRED (409)
        SeededPlan validPlan = seedPlan(now.plusSeconds(60), 1L, "PREVIEWED", "RUNNING");
        String wrongHash = "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
        BusinessException hashMismatch = assertThrows(
                BusinessException.class,
                () -> submitOrder.execute(
                        validPlan.ownerId(), "key-hash",
                        new SubmitOrderCommand(validPlan.planId(), validPlan.revision(), wrongHash)
                )
        );
        assertEquals(BusinessException.Code.REVALIDATION_REQUIRED, hashMismatch.code());

        // 2. Başka kullanıcının planı -> NOT_FOUND (404)
        BusinessException wrongOwner = assertThrows(
                BusinessException.class,
                () -> submitOrder.execute(
                        UUID.randomUUID(), "key-owner",
                        new SubmitOrderCommand(validPlan.planId(), validPlan.revision(), validPlan.hash())
                )
        );
        assertEquals(BusinessException.Code.NOT_FOUND, wrongOwner.code());

        // 3. Süresi dolmuş plan -> PLAN_EXPIRED (409)
        SeededPlan expiredPlan = seedPlan(now.minusSeconds(1), 1L, "PREVIEWED", "RUNNING");
        BusinessException expired = assertThrows(
                BusinessException.class,
                () -> submitOrder.execute(
                        expiredPlan.ownerId(), "key-exp",
                        new SubmitOrderCommand(expiredPlan.planId(), expiredPlan.revision(), expiredPlan.hash())
                )
        );
        assertEquals(BusinessException.Code.PLAN_EXPIRED, expired.code());

        // 4. Generation değişmiş (close sonrası) -> PLAN_REVOKED (409)
        SeededPlan genPlan = seedPlan(now.plusSeconds(60), 2L, "PREVIEWED", "RUNNING");
        BusinessException revoked = assertThrows(
                BusinessException.class,
                () -> submitOrder.execute(
                        genPlan.ownerId(), "key-gen",
                        new SubmitOrderCommand(genPlan.planId(), genPlan.revision(), genPlan.hash())
                )
        );
        assertEquals(BusinessException.Code.PLAN_REVOKED, revoked.code());

        // 5. HALTED session -> SESSION_HALTED (422)
        SeededPlan haltedPlan = seedPlan(now.plusSeconds(60), 1L, "PREVIEWED", "HALTED");
        BusinessException halted = assertThrows(
                BusinessException.class,
                () -> submitOrder.execute(
                        haltedPlan.ownerId(), "key-halt",
                        new SubmitOrderCommand(haltedPlan.planId(), haltedPlan.revision(), haltedPlan.hash())
                )
        );
        assertEquals(BusinessException.Code.SESSION_HALTED, halted.code());
    }

    @Test
    void fillAccountingWithLedgerMatchesGoldenF01OpenAndF03CloseBalances() {
        UUID ownerId = UUID.randomUUID();
        UUID sessionId = createSession.execute(ownerId, new BigDecimal("10000"), UUID.randomUUID(), UUID.randomUUID());

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
                    (?, ?, 'ISOLATED:POS1', 'ISOLATED'),
                    (?, ?, 'SIM_FEES', 'SYSTEM'),
                    (?, ?, 'SIM_PNL_POOL', 'SYSTEM'),
                    (?, ?, 'SIM_INSURANCE', 'SYSTEM')
                """,
                isolatedId, sessionId,
                feesId, sessionId,
                pnlPoolId, sessionId,
                insuranceId, sessionId
        );

        Instant now = clock.instant();
        var tx = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource())
        );

        // F01: E=50000, q=0.2, L=10 -> A=8995, B=1000
        var openCalc = Finance.open(
                Decimal.parse("1000"), Decimal.parse("10"), Decimal.parse("50000"),
                Decimal.parse("0.001"), Decimal.parse("0.0005"), Decimal.parse("10000")
        );
        Journal openJournal = new Journal(
                UUID.randomUUID(), sessionId, "open-fill:" + sessionId, "open-hash",
                "OPEN_FILL", FillAccounting.opening(availableId, isolatedId, feesId, openCalc)
        );
        tx.executeWithoutResult(s -> ledger.post(openJournal, now, now));

        BigDecimal availableAfterOpen = jdbc.queryForObject(
                "SELECT balance FROM wallet_accounts WHERE id = ?", BigDecimal.class, availableId
        );
        BigDecimal isolatedAfterOpen = jdbc.queryForObject(
                "SELECT balance FROM wallet_accounts WHERE id = ?", BigDecimal.class, isolatedId
        );
        assertEquals(0, new BigDecimal("8995").compareTo(availableAfterOpen));
        assertEquals(0, new BigDecimal("1000").compareTo(isolatedAfterOpen));

        // F03: 50500 normal close -> A=10089.95, B=0
        var closeCalc = Finance.close(
                PositionSide.LONG, Decimal.parse("0.2"), Decimal.parse("0.2"),
                Decimal.parse("50000"), Decimal.parse("50500"), Decimal.parse("1000"), Decimal.parse("0.0005")
        );
        Journal closeJournal = new Journal(
                UUID.randomUUID(), sessionId, "close-fill:" + sessionId, "close-hash",
                "CLOSE_FILL", FillAccounting.closing(availableId, isolatedId, pnlPoolId, feesId, insuranceId, closeCalc)
        );
        tx.executeWithoutResult(s -> ledger.post(closeJournal, now, now));

        BigDecimal availableAfterClose = jdbc.queryForObject(
                "SELECT balance FROM wallet_accounts WHERE id = ?", BigDecimal.class, availableId
        );
        BigDecimal isolatedAfterClose = jdbc.queryForObject(
                "SELECT balance FROM wallet_accounts WHERE id = ?", BigDecimal.class, isolatedId
        );
        assertEquals(0, new BigDecimal("10089.95").compareTo(availableAfterClose));
        assertEquals(0, BigDecimal.ZERO.compareTo(isolatedAfterClose));
    }
}
