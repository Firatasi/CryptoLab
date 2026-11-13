package com.crypto.simulationservice;

import com.crypto.financecore.finance.Decimal;
import com.crypto.simulationservice.api.controller.OrderController;
import com.crypto.simulationservice.api.controller.SessionController;
import com.crypto.simulationservice.api.dto.request.SubmitOrderRequest;
import com.crypto.simulationservice.api.exception.ApiErrors;
import com.crypto.simulationservice.application.service.CreateSession;
import com.crypto.simulationservice.application.service.OrderExecutionService;
import com.crypto.simulationservice.application.service.PlanHash;
import com.crypto.simulationservice.application.service.SimulationMarketCatalog;
import com.crypto.simulationservice.application.service.SimulationReportingService;
import com.crypto.simulationservice.domain.model.ExecutionPlan;
import com.crypto.simulationservice.domain.service.PreviewFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OrderExecutionAndValidationIT {

    private static final UUID RULE_PROFILE = UUID.fromString("88888888-8888-4888-8888-888888888888");
    private static final UUID RISK_POLICY_50X = UUID.fromString("99999999-9999-4999-8999-999999999997");
    private static final UUID BTC_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

    @Autowired
    CreateSession createSession;

    @Autowired
    OrderController orderController;

    @Autowired
    SessionController sessionController;

    @Autowired
    OrderExecutionService orderExecutionService;

    @Autowired
    SimulationMarketCatalog catalog;

    @Autowired
    ApiErrors apiErrors;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Clock clock;

    @Test
    @Transactional
    @SuppressWarnings("unchecked")
    void listPositionsFlushesAcceptedSubmitCommandsEvenWhenCommandEndpointWasNotPolled() {
        UUID owner = UUID.randomUUID();
        UUID sessionId = createSession.execute(owner, new BigDecimal("5000"), RULE_PROFILE, RISK_POLICY_50X);
        BigDecimal entry = catalog.fetchLiveMarkPrice(BTC_ID, new BigDecimal("70000"));
        BigDecimal sl = entry.multiply(new BigDecimal("0.92")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal tp = entry.multiply(new BigDecimal("1.08")).setScale(2, RoundingMode.HALF_UP);

        UUID planId = seedConsumablePlan(
                sessionId, BTC_ID, "AI_PROPOSAL", "LONG", "MARKET",
                entry, null, new BigDecimal("0.01"), new BigDecimal("350"), new BigDecimal("0.28"),
                new BigDecimal("2"), sl, tp
        );

        Jwt jwt = jwtFor(owner);
        var accepted = orderController.submit(jwt, "flush-" + UUID.randomUUID(), new SubmitOrderRequest(
                planId, 1, readHash(planId, 1)
        )).getBody();
        assertNotNull(accepted);
        assertEquals("ACCEPTED", accepted.status());

        // Emrin /commands/{id} cagrilmadan dogrudan /positions cagrildiginda da pozisyon olusmalidir.
        Map<String, Object> positionsResp = sessionController.listPositions(jwt, sessionId.toString());
        List<Map<String, Object>> items = (List<Map<String, Object>>) positionsResp.get("items");
        assertEquals(1, items.size());
        assertEquals("LONG", items.getFirst().get("side"));
        assertEquals("OPEN", items.getFirst().get("status"));
        assertEquals("FILLED", jdbc.queryForObject("SELECT status FROM commands WHERE id = ?", String.class, accepted.commandId()));

        // Tekrar cagrildiginda cift pozisyon olusmaz
        Map<String, Object> secondPositions = sessionController.listPositions(jwt, sessionId.toString());
        assertEquals(1, ((List<?>) secondPositions.get("items")).size());
    }

    @Test
    @Transactional
    @SuppressWarnings("unchecked")
    void restingLimitOrderStaysNewUntilMarkHitsLimitThenFillsIntoOpenPosition() {
        UUID owner = UUID.randomUUID();
        UUID sessionId = createSession.execute(owner, new BigDecimal("10000"), RULE_PROFILE, RISK_POLICY_50X);

        BigDecimal limitPrice = new BigDecimal("65000.00");
        BigDecimal stopLoss = new BigDecimal("63500.00");
        BigDecimal takeProfit = new BigDecimal("70000.00");
        UUID planId = seedConsumablePlan(
                sessionId, BTC_ID, "MANUAL", "LONG", "LIMIT",
                limitPrice, limitPrice, new BigDecimal("0.02"), new BigDecimal("130"), new BigDecimal("0.26"),
                new BigDecimal("10"), stopLoss, takeProfit
        );

        Jwt jwt = jwtFor(owner);
        var accepted = orderController.submit(jwt, "limit-" + UUID.randomUUID(), new SubmitOrderRequest(
                planId, 1, readHash(planId, 1)
        )).getBody();
        assertNotNull(accepted);

        // Henuz mark (68000) LONG limit (65000) uzerinde oldugu icin emir acik limit emri olarak kalir
        orderExecutionService.materializeCommandIfPending(accepted.commandId(), id -> Optional.of(new BigDecimal("68000")));
        assertEquals("NEW", jdbc.queryForObject("SELECT status FROM orders WHERE command_id = ?", String.class, accepted.commandId()));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM positions WHERE session_id = ?", Integer.class, sessionId));

        Map<String, Object> openOrders = sessionController.listOpenOrders(jwt, sessionId.toString());
        List<Map<String, Object>> orderItems = (List<Map<String, Object>>) openOrders.get("items");
        assertEquals(1, orderItems.size());
        assertEquals("10", orderItems.getFirst().get("leverage"));
        assertEquals("63500", orderItems.getFirst().get("stopLoss"));
        assertEquals("70000", orderItems.getFirst().get("takeProfit"));

        // Mark henuz tetiklenmedi -> eslesme olmamali
        assertEquals(0, orderExecutionService.matchOpenLimitOrders(sessionId, id -> Optional.of(new BigDecimal("65500"))));

        // Mark limit fiyatina veya altina indiginde -> emir FILLED ve pozisyon OPEN olmali
        assertEquals(1, orderExecutionService.matchOpenLimitOrders(sessionId, id -> Optional.of(new BigDecimal("64950"))));
        assertEquals("FILLED", jdbc.queryForObject("SELECT status FROM orders WHERE command_id = ?", String.class, accepted.commandId()));
        assertEquals("FILLED", jdbc.queryForObject("SELECT status FROM commands WHERE id = ?", String.class, accepted.commandId()));

        UUID posId = jdbc.queryForObject("SELECT position_id FROM orders WHERE command_id = ?", UUID.class, accepted.commandId());
        assertNotNull(posId);
        var posRow = jdbc.queryForMap("SELECT state, entry_price, stop_loss, take_profit FROM positions WHERE id = ?", posId);
        assertEquals("OPEN", posRow.get("state"));
        assertEquals(0, limitPrice.compareTo((BigDecimal) posRow.get("entry_price")));
        assertEquals(0, stopLoss.compareTo((BigDecimal) posRow.get("stop_loss")));
        assertEquals(0, takeProfit.compareTo((BigDecimal) posRow.get("take_profit")));
    }

    @Test
    @Transactional
    void planWithHighPrecisionMarginFromNonDecimalLeverageMaterializesWithoutDecimalOutOfRange() {
        UUID owner = UUID.randomUUID();
        UUID sessionId = createSession.execute(owner, new BigDecimal("5000"), RULE_PROFILE, RISK_POLICY_50X);
        // 12x kaldiracta DECIMAL128 34 basamakli marginRequired degeri uretilir
        BigDecimal qty = new BigDecimal("0.01");
        BigDecimal entry = new BigDecimal("68865.97");
        BigDecimal repeatingMargin = qty.multiply(entry).divide(new BigDecimal("12"), com.crypto.financecore.finance.Decimal.MC);
        assertTrue(repeatingMargin.scale() > 18);

        UUID planId = seedConsumablePlan(
                sessionId, BTC_ID, "MANUAL", "LONG", "LIMIT",
                entry, new BigDecimal("55000.00"), qty, repeatingMargin, new BigDecimal("0.21"),
                new BigDecimal("12"), new BigDecimal("52000.00"), new BigDecimal("72000.00")
        );

        var accepted = orderController.submit(jwtFor(owner), "prec-" + UUID.randomUUID(), new SubmitOrderRequest(
                planId, 1, readHash(planId, 1)
        )).getBody();
        assertNotNull(accepted);

        orderExecutionService.materializeCommandIfPending(accepted.commandId(), id -> Optional.of(entry));
        assertEquals("NEW", jdbc.queryForObject("SELECT status FROM orders WHERE command_id = ?", String.class, accepted.commandId()));
        assertEquals("NEW", jdbc.queryForObject("SELECT status FROM commands WHERE id = ?", String.class, accepted.commandId()));
    }

    @Test
    @Transactional
    void stopLossPastLiquidationIsRejectedInPreviewAndUpdateBrackets() {
        UUID sessionId = UUID.randomUUID();
        UUID ruleId = RULE_PROFILE;
        Instant now = clock.instant();

        // 50x LONG @ 50000, margin=100 -> qty=0.1, liq ~49246.23; $100 zararda SL=49000 < liq -> reddedilmeli
        var rule = new PreviewFactory.Rule(
                ruleId, new BigDecimal("0.01"), new BigDecimal("0.001"),
                new BigDecimal("5"), new BigDecimal("0.0004"),
                SimulationReportingService.DEFAULT_MAINTENANCE_TIERS
        );
        var ctx = new PreviewFactory.Context(
                new BigDecimal("49999.99"), new BigDecimal("50000.01"),
                new BigDecimal("1000"), BigDecimal.ZERO, 1L, 60L,
                new BigDecimal("45000"), new BigDecimal("55000"),
                new BigDecimal("1000"), false, false
        );
        var unsafeReq = new PreviewFactory.Request(
                sessionId, BTC_ID, 1L, 1L, "LONG", "MARKET",
                new BigDecimal("100"), new BigDecimal("50"), null,
                new BigDecimal("49000"), new BigDecimal("51000"), 3600, "CLOSE_AT_DEADLINE"
        );

        var ex = assertThrows(IllegalArgumentException.class, () -> PreviewFactory.create(unsafeReq, ctx, rule, now));
        assertEquals("STOP_LOSS_PAST_LIQUIDATION", ex.getMessage());
        assertEquals(400, apiErrors.legacy(ex).getStatusCode().value());
        assertEquals("STOP_LOSS_PAST_LIQUIDATION", apiErrors.legacy(ex).getBody().code());

        // Guvenli SL (49500 > 49246.23) -> onaylanmali
        var safeReq = new PreviewFactory.Request(
                sessionId, BTC_ID, 1L, 1L, "LONG", "MARKET",
                new BigDecimal("100"), new BigDecimal("50"), null,
                new BigDecimal("49500"), new BigDecimal("51000"), 3600, "CLOSE_AT_DEADLINE"
        );
        ExecutionPlan okPlan = PreviewFactory.create(safeReq, ctx, rule, now);
        assertNotNull(okPlan.estimatedLiquidationPrice());
        assertTrue(okPlan.stopLoss().compareTo(okPlan.estimatedLiquidationPrice()) > 0);

        // Acik pozisyon uzerinde updateBrackets da SL >= liq (SHORT) veya <= liq (LONG) degerlerini reddetmeli
        UUID owner = UUID.randomUUID();
        UUID realSession = createSession.execute(owner, new BigDecimal("1000"), RULE_PROFILE, RISK_POLICY_50X);
        BigDecimal liveMark = catalog.fetchLiveMarkPrice(BTC_ID, new BigDecimal("70000"));
        BigDecimal posMargin = liveMark.multiply(new BigDecimal("0.1")).divide(new BigDecimal("50"), 2, RoundingMode.HALF_UP);
        BigDecimal validSl = liveMark.multiply(new BigDecimal("0.992")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal validTp = liveMark.multiply(new BigDecimal("1.03")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal unsafeSlBelowLiq = liveMark.multiply(new BigDecimal("0.975")).setScale(2, RoundingMode.HALF_UP);

        UUID posId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO positions(
                    id, session_id, instrument_id, side, q, entry_price,
                    original_margin, initial_margin_remaining, state, generation,
                    version, stop_loss, take_profit, deadline_at, expiry_policy, horizon_seconds, close_requested, opened_at
                )
                VALUES (?, ?, ?, 'LONG', 0.1, ?, ?, ?, 'OPEN', 1, 1, ?, ?, ?, 'CLOSE_AT_DEADLINE', 3600, false, ?)
                """, posId, realSession, BTC_ID, liveMark, posMargin, posMargin, validSl, validTp,
                Timestamp.from(now.plusSeconds(3600)), Timestamp.from(now));

        // 50x LONG pozisyonda SL < mark ama <= tasfiye fiyati (~%98.5) -> STOP_LOSS_PAST_LIQUIDATION
        var updateEx = assertThrows(IllegalArgumentException.class, () ->
                sessionController.updateBrackets(jwtFor(owner), posId, Map.of("stopLoss", unsafeSlBelowLiq.toPlainString()))
        );
        assertEquals("STOP_LOSS_PAST_LIQUIDATION", updateEx.getMessage());
    }

    private UUID seedConsumablePlan(
            UUID sessionId,
            UUID instrumentId,
            String source,
            String side,
            String orderType,
            BigDecimal referenceEntry,
            BigDecimal limitPrice,
            BigDecimal quantity,
            BigDecimal margin,
            BigDecimal fee,
            BigDecimal leverage,
            BigDecimal stopLoss,
            BigDecimal takeProfit
    ) {
        UUID planId = UUID.randomUUID();
        Instant now = clock.instant();
        jdbc.update("""
                INSERT INTO instrument_generations(session_id, instrument_id, generation)
                VALUES (?, ?, 1)
                ON CONFLICT DO NOTHING
                """, sessionId, instrumentId);

        ExecutionPlan plan = new ExecutionPlan(
                planId, 1, null, sessionId, instrumentId, 1L,
                source, "AI_PROPOSAL".equals(source) ? UUID.randomUUID() : null, 1L,
                side, orderType, quantity, referenceEntry, limitPrice,
                margin, fee, quantity.multiply(referenceEntry), leverage,
                stopLoss, takeProfit, 3600, "CLOSE_AT_DEADLINE",
                referenceEntry.multiply(new BigDecimal("0.90")),
                referenceEntry.multiply(new BigDecimal("1.10")),
                fee.add(BigDecimal.ONE), BigDecimal.ZERO,
                RULE_PROFILE, 1L, now, now.plusSeconds(60),
                referenceEntry.multiply(new BigDecimal("0.70")), List.of()
        );
        String canonicalJson = new String(PlanHash.canonical(plan), StandardCharsets.UTF_8);
        String hash = PlanHash.hash(plan);
        jdbc.update("""
                INSERT INTO execution_plans(
                    plan_id, revision, session_id, instrument_id,
                    canonical_json, hash, position_generation, valid_until, state
                )
                VALUES (?, 1, ?, ?, ?::jsonb, ?, 1, ?, 'PREVIEWED')
                """, planId, sessionId, instrumentId, canonicalJson, hash, Timestamp.from(now.plusSeconds(60)));
        return planId;
    }

    private String readHash(UUID planId, int revision) {
        return jdbc.queryForObject(
                "SELECT hash FROM execution_plans WHERE plan_id = ? AND revision = ?",
                String.class, planId, revision
        );
    }

    private static Jwt jwtFor(UUID ownerId) {
        return Jwt.withTokenValue("test-jwt")
                .header("alg", "none")
                .subject(ownerId.toString())
                .build();
    }
}
