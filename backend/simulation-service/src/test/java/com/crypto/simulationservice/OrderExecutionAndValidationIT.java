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
    SimulationReportingService reportingService;

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

    @Test
    @Transactional
    @SuppressWarnings("unchecked")
    void openingSameInstrumentPositionsViaMarketAndLimitKeepsAllOpenAndClosesOnlyTargetedPosition() {
        UUID owner = UUID.randomUUID();
        UUID sessionId = createSession.execute(owner, new BigDecimal("10000"), RULE_PROFILE, RISK_POLICY_50X);
        Jwt jwt = jwtFor(owner);
        BigDecimal liveMark = catalog.fetchLiveMarkPrice(BTC_ID, new BigDecimal("68000.00")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal sl1 = liveMark.multiply(new BigDecimal("0.92")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal tp1 = liveMark.multiply(new BigDecimal("1.08")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal sl2 = liveMark.multiply(new BigDecimal("0.93")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal tp2 = liveMark.multiply(new BigDecimal("1.10")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal updatedSl2 = liveMark.multiply(new BigDecimal("0.95")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal updatedTp2 = liveMark.multiply(new BigDecimal("1.12")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal limit3 = liveMark.multiply(new BigDecimal("1.02")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal sl3 = liveMark.multiply(new BigDecimal("1.10")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal tp3 = liveMark.multiply(new BigDecimal("0.85")).setScale(2, RoundingMode.HALF_UP);

        // 1. Aynı enstrümanda (BTCUSDT) ilk LONG MARKET pozisyonu
        UUID plan1 = seedConsumablePlan(
                sessionId, BTC_ID, "MANUAL", "LONG", "MARKET",
                liveMark, null, new BigDecimal("0.01"),
                new BigDecimal("340.00"), new BigDecimal("0.27"),
                new BigDecimal("2"), sl1, tp1
        );
        var acc1 = orderController.submit(jwt, "same-1-" + UUID.randomUUID(), new SubmitOrderRequest(
                plan1, 1, readHash(plan1, 1)
        )).getBody();
        assertNotNull(acc1);
        orderExecutionService.materializeCommandIfPending(acc1.commandId(), id -> Optional.of(liveMark));
        UUID pos1Id = jdbc.queryForObject("SELECT position_id FROM orders WHERE command_id = ?", UUID.class, acc1.commandId());
        assertNotNull(pos1Id);

        // 2. Mevcut açık pozisyonu kapatmadan aynı enstrümanda ikinci LONG MARKET pozisyonu
        UUID plan2 = seedConsumablePlan(
                sessionId, BTC_ID, "MANUAL", "LONG", "MARKET",
                liveMark, null, new BigDecimal("0.02"),
                new BigDecimal("680.00"), new BigDecimal("0.54"),
                new BigDecimal("2"), sl2, tp2
        );
        var acc2 = orderController.submit(jwt, "same-2-" + UUID.randomUUID(), new SubmitOrderRequest(
                plan2, 1, readHash(plan2, 1)
        )).getBody();
        assertNotNull(acc2);
        orderExecutionService.materializeCommandIfPending(acc2.commandId(), id -> Optional.of(liveMark));
        UUID pos2Id = jdbc.queryForObject("SELECT position_id FROM orders WHERE command_id = ?", UUID.class, acc2.commandId());
        assertNotNull(pos2Id);
        assertNotEquals(pos1Id, pos2Id);

        // 3. Aynı enstrümanda karşı yönlü SHORT LIMIT emri -> tetiklendiğinde önceki iki pozisyon da AÇIK kalmalı
        UUID plan3 = seedConsumablePlan(
                sessionId, BTC_ID, "MANUAL", "SHORT", "LIMIT",
                limit3, limit3, new BigDecimal("0.015"),
                new BigDecimal("517.50"), new BigDecimal("0.41"),
                new BigDecimal("2"), sl3, tp3
        );
        var acc3 = orderController.submit(jwt, "same-3-" + UUID.randomUUID(), new SubmitOrderRequest(
                plan3, 1, readHash(plan3, 1)
        )).getBody();
        assertNotNull(acc3);
        orderExecutionService.materializeCommandIfPending(acc3.commandId(), id -> Optional.of(liveMark));
        assertEquals("NEW", jdbc.queryForObject("SELECT status FROM orders WHERE command_id = ?", String.class, acc3.commandId()));

        Map<String, Object> sessionWithPendingLimit = sessionController.getSession(jwt, sessionId.toString());
        assertEquals(0, new BigDecimal("517.91").compareTo(new BigDecimal((String) sessionWithPendingLimit.get("reservedBalance"))));
        assertEquals(0, new BigDecimal("1020.00").compareTo(new BigDecimal((String) sessionWithPendingLimit.get("isolatedMargin"))));
        assertEquals(0, new BigDecimal("8461.28").compareTo(new BigDecimal((String) sessionWithPendingLimit.get("availableBalance"))));

        int filledLimit = orderExecutionService.matchOpenLimitOrders(sessionId, id -> Optional.of(limit3.add(new BigDecimal("10.00"))));
        assertEquals(1, filledLimit);
        UUID pos3Id = jdbc.queryForObject("SELECT position_id FROM orders WHERE command_id = ?", UUID.class, acc3.commandId());
        assertNotNull(pos3Id);

        // Üç pozisyonun da eşzamanlı AÇIK olduğunu ve kullanılabilir bakiyenin doğru korunduğunu doğrula
        List<Map<String, Object>> openItems = (List<Map<String, Object>>) sessionController.listPositions(jwt, sessionId.toString()).get("items");
        assertEquals(3, openItems.size());

        BigDecimal availableAfterThree = jdbc.queryForObject(
                "SELECT balance FROM wallet_accounts WHERE session_id = ? AND kind = 'AVAILABLE'",
                BigDecimal.class, sessionId
        );
        assertEquals(0, new BigDecimal("8461.28").compareTo(availableAfterThree));

        Map<String, Object> sessionView = sessionController.getSession(jwt, sessionId.toString());
        assertEquals(3, sessionView.get("openPositionCount"));
        assertEquals(0, new BigDecimal("1537.50").compareTo(new BigDecimal((String) sessionView.get("isolatedMargin"))));
        assertEquals(0, BigDecimal.ZERO.compareTo(new BigDecimal((String) sessionView.get("reservedBalance"))));

        // Yalnız 2. pozisyonun SL/TP seviyelerini güncelle; 1. ve 3. pozisyonlar etkilenmemeli
        sessionController.updateBrackets(jwt, pos2Id, Map.of("stopLoss", updatedSl2.toPlainString(), "takeProfit", updatedTp2.toPlainString()));
        assertEquals(0, sl1.compareTo(
                jdbc.queryForObject("SELECT stop_loss FROM positions WHERE id = ?", BigDecimal.class, pos1Id)));
        assertEquals(0, updatedSl2.compareTo(
                jdbc.queryForObject("SELECT stop_loss FROM positions WHERE id = ?", BigDecimal.class, pos2Id)));
        assertEquals(0, sl3.compareTo(
                jdbc.queryForObject("SELECT stop_loss FROM positions WHERE id = ?", BigDecimal.class, pos3Id)));

        // Yalnız 1. pozisyonu kapat -> diğer 2 BTCUSDT pozisyonu AÇIK kalmalı
        sessionController.closePosition(jwt, pos1Id);
        assertEquals("CLOSED", jdbc.queryForObject("SELECT state FROM positions WHERE id = ?", String.class, pos1Id));
        assertEquals("OPEN", jdbc.queryForObject("SELECT state FROM positions WHERE id = ?", String.class, pos2Id));
        assertEquals("OPEN", jdbc.queryForObject("SELECT state FROM positions WHERE id = ?", String.class, pos3Id));
        List<Map<String, Object>> afterSingleClose = (List<Map<String, Object>>) sessionController.listPositions(jwt, sessionId.toString()).get("items");
        assertEquals(2, afterSingleClose.size());

        // Otomatik çıkış: updatedSl2 - 10 fiyatı yalnızca pos2 SL tetikler; pos3 (SHORT tp=0.85x) AÇIK kalır
        BigDecimal triggerSl2 = updatedSl2.subtract(new BigDecimal("10.00"));
        var autoEvents = reportingService.processAutomaticExits(
                owner,
                sessionId,
                id -> Optional.of(triggerSl2),
                clock.instant().plusSeconds(10)
        );
        assertEquals(1, autoEvents.size());
        assertEquals(pos2Id, autoEvents.getFirst().positionId());
        assertEquals("CLOSED", jdbc.queryForObject("SELECT state FROM positions WHERE id = ?", String.class, pos2Id));
        assertEquals("OPEN", jdbc.queryForObject("SELECT state FROM positions WHERE id = ?", String.class, pos3Id));
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
