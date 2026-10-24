package com.crypto.simulationservice;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import com.crypto.financecore.finance.Decimal;
import com.crypto.financecore.finance.Finance;
import com.crypto.financecore.finance.MaintenanceTier;
import com.crypto.financecore.finance.PositionSide;
import com.crypto.simulationservice.api.dto.request.ManualPreviewRequest;
import com.crypto.simulationservice.api.dto.request.SubmitOrderRequest;
import com.crypto.simulationservice.api.exception.ApiErrors;
import com.crypto.simulationservice.api.mapper.OrderMapper;
import com.crypto.simulationservice.application.command.SubmitOrderCommand;
import com.crypto.simulationservice.application.service.PlanHash;
import com.crypto.simulationservice.application.service.SubmitOrder;
import com.crypto.simulationservice.domain.exception.BusinessException;
import com.crypto.simulationservice.domain.model.ExecutionPlan;
import com.crypto.simulationservice.domain.model.Journal;
import com.crypto.simulationservice.domain.service.ExecutionGate;
import com.crypto.simulationservice.domain.service.FillAccounting;
import com.crypto.simulationservice.domain.service.LimitDecision;
import com.crypto.simulationservice.domain.service.PreviewFactory;
import com.crypto.simulationservice.infrastructure.config.DecimalJsonConfig;
import com.fasterxml.jackson.databind.ObjectMapper;

class ExecutionAndPreviewTest {

    private static BigDecimal d(String value) {
        return Decimal.parse(value);
    }

    private static ExecutionPlan samplePlan(BigDecimal quantity, BigDecimal entry, String hashOverride, List<String> warnings) {
        return new ExecutionPlan(
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                1,
                hashOverride,
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                UUID.fromString("33333333-3333-4333-8333-333333333333"),
                1L,
                "MANUAL",
                null,
                1L,
                "LONG",
                "MARKET",
                quantity,
                entry,
                null,
                d("1000"),
                d("5"),
                d("10000"),
                d("10"),
                d("48000"),
                d("55000"),
                3600,
                "CLOSE_AT_DEADLINE",
                d("49900"),
                d("50100"),
                d("5.01"),
                d("0"),
                UUID.fromString("44444444-4444-4444-8444-444444444444"),
                1L,
                Instant.parse("2026-10-19T12:00:00Z"),
                Instant.parse("2026-10-19T12:00:30Z"),
                d("45180.722891"),
                warnings
        );
    }

    @Test
    void planHashCanonicalMatchesFixtureAndNormalizesTrailingZeros() throws Exception {
        ExecutionPlan p1 = samplePlan(new BigDecimal("0.20"), new BigDecimal("50000.0"), null, List.of());
        ExecutionPlan p2 = samplePlan(new BigDecimal("0.2000"), new BigDecimal("50000.000"), "sha256:ignored", List.of("WARN_1"));
        ExecutionPlan differentQty = samplePlan(new BigDecimal("0.201"), new BigDecimal("50000"), null, List.of());

        String canonical1 = new String(PlanHash.canonical(p1), StandardCharsets.UTF_8);
        String canonical2 = new String(PlanHash.canonical(p2), StandardCharsets.UTF_8);

        assertEquals(canonical1, canonical2);
        assertEquals(PlanHash.hash(p1), PlanHash.hash(p2));
        assertNotEquals(PlanHash.hash(p1), PlanHash.hash(differentQty));
        assertTrue(PlanHash.hash(p1).matches("^sha256:[0-9a-f]{64}$"));

        Path fixturePath = Path.of("..", "..", "contracts", "fixtures", "execution-plan.canonical.json");
        if (!Files.exists(fixturePath)) {
            fixturePath = Path.of("..", "contracts", "fixtures", "execution-plan.canonical.json");
        }
        String expectedFixture = Files.readString(fixturePath, StandardCharsets.UTF_8).strip();
        assertEquals(expectedFixture, canonical1);
    }

    @Test
    void executionGateValidatesFreshnessBracketsAndApprovedPlan() {
        Instant now = Instant.parse("2026-10-19T12:00:10Z");
        ExecutionGate.fresh(now.minusMillis(1000), now.minusMillis(500), now, 2000, 1000, "STALE_QUOTE");

        IllegalStateException stale = assertThrows(
                IllegalStateException.class,
                () -> ExecutionGate.fresh(now.minusMillis(2500), now.minusMillis(500), now, 2000, 1000, "STALE_QUOTE")
        );
        assertEquals("STALE_QUOTE", stale.getMessage());

        IllegalStateException skewed = assertThrows(
                IllegalStateException.class,
                () -> ExecutionGate.fresh(now.plusMillis(1500), now, now, 2000, 1000, "STALE_MARK")
        );
        assertEquals("STALE_MARK", skewed.getMessage());

        ExecutionGate.bracket("LONG", d("50000"), d("49000"), d("51000"), true, true);
        ExecutionGate.bracket("SHORT", d("50000"), d("51000"), d("49000"), true, true);
        ExecutionGate.bracket("LONG", d("50000"), null, null, false, false);

        assertThrows(IllegalArgumentException.class, ()
                -> ExecutionGate.bracket("LONG", d("50000"), null, d("51000"), true, false));
        assertThrows(IllegalArgumentException.class, ()
                -> ExecutionGate.bracket("LONG", d("50000"), d("50000"), d("51000"), false, false));
        assertThrows(IllegalArgumentException.class, ()
                -> ExecutionGate.bracket("SHORT", d("50000"), d("50000"), d("49000"), false, false));

        ExecutionPlan plan = samplePlan(d("0.2"), d("50000"), null, List.of());
        ExecutionGate.approved(plan, now, 1L, d("50000"), d("5"));

        assertEquals("PLAN_EXPIRED", assertThrows(IllegalStateException.class, ()
                -> ExecutionGate.approved(plan, Instant.parse("2026-10-19T12:00:30Z"), 1L, d("50000"), d("5"))).getMessage());
        assertEquals("PLAN_REVOKED", assertThrows(IllegalStateException.class, ()
                -> ExecutionGate.approved(plan, now, 2L, d("50000"), d("5"))).getMessage());
        assertEquals("REVALIDATION_REQUIRED", assertThrows(IllegalStateException.class, ()
                -> ExecutionGate.approved(plan, now, 1L, d("50200"), d("5"))).getMessage());
        assertEquals("REVALIDATION_REQUIRED", assertThrows(IllegalStateException.class, ()
                -> ExecutionGate.approved(plan, now, 1L, d("50000"), d("5.02"))).getMessage());
    }

    @Test
    void previewFactoryComputesGoldenPlanAndEnforcesValidationRules() {
        var tier = new MaintenanceTier(d("0"), null, d("0.004"), d("0"));
        var rule = new PreviewFactory.Rule(
                UUID.randomUUID(), d("0.1"), d("0.001"), d("100"), d("0.0005"), List.of(tier)
        );
        Instant now = Instant.parse("2026-10-19T12:00:00Z");

        var req = new PreviewFactory.Request(
                UUID.randomUUID(), UUID.randomUUID(), 1L, 1L,
                "LONG", "MARKET", d("1000"), d("10"),
                null, d("48000"), d("55000"), 3600, "CLOSE_AT_DEADLINE"
        );
        var ctx = new PreviewFactory.Context(
                d("50000"), d("50000"), d("10000"), d("0"), 1L,
                30L, d("49900"), d("50100"), d("5"), false, false
        );

        ExecutionPlan plan = PreviewFactory.create(req, ctx, rule, now);
        assertEquals(0, d("0.2").compareTo(plan.quantity()));
        assertEquals(0, d("50000").compareTo(plan.referenceEntry()));
        assertEquals(0, d("1000").compareTo(plan.marginRequired()));
        assertEquals(0, d("5").compareTo(plan.openFeeEstimate()));
        assertEquals(0, d("10000").compareTo(plan.notional()));
        assertNotNull(plan.estimatedLiquidationPrice());

        // F14: marginBudget=1000, available=1000 -> fee dahil yetersiz bakiye reddedilir.
        var insufficientCtx = new PreviewFactory.Context(
                d("50000"), d("50000"), d("1000"), d("0"), 1L,
                30L, d("49900"), d("50100"), d("5"), false, false
        );
        assertEquals("INSUFFICIENT_AVAILABLE_BALANCE", assertThrows(IllegalArgumentException.class, ()
                -> PreviewFactory.create(req, insufficientCtx, rule, now)).getMessage());

        // maxFee < open.fee() reddedilir.
        var lowMaxFeeCtx = new PreviewFactory.Context(
                d("50000"), d("50000"), d("10000"), d("0"), 1L,
                30L, d("49900"), d("50100"), d("4.99"), false, false
        );
        assertEquals("INVALID_APPROVAL_BOUNDS", assertThrows(IllegalArgumentException.class, ()
                -> PreviewFactory.create(req, lowMaxFeeCtx, rule, now)).getMessage());

        // MARKET emirde limitPrice olamaz.
        var marketWithLimit = new PreviewFactory.Request(
                UUID.randomUUID(), UUID.randomUUID(), 1L, 1L,
                "LONG", "MARKET", d("1000"), d("10"),
                d("50000"), null, null, 3600, "CLOSE_AT_DEADLINE"
        );
        assertEquals("LIMIT_PRICE_REQUIRED_OR_FORBIDDEN", assertThrows(IllegalArgumentException.class, ()
                -> PreviewFactory.create(marketWithLimit, ctx, rule, now)).getMessage());

        // LIMIT fiyat tick'e bölünmeli.
        var invalidTickLimit = new PreviewFactory.Request(
                UUID.randomUUID(), UUID.randomUUID(), 1L, 1L,
                "LONG", "LIMIT", d("1000"), d("10"),
                d("50000.05"), null, null, 3600, "CLOSE_AT_DEADLINE"
        );
        assertEquals("INVALID_TICK", assertThrows(IllegalArgumentException.class, ()
                -> PreviewFactory.create(invalidTickLimit, ctx, rule, now)).getMessage());
    }

    @Test
    void limitDecisionEvaluatesWaitRejectAndFill() {
        ExecutionPlan limitPlan = new ExecutionPlan(
                UUID.randomUUID(), 1, null, UUID.randomUUID(), UUID.randomUUID(), 1L,
                "MANUAL", null, 1L, "LONG", "LIMIT", d("0.2"), d("50000"), d("50000"),
                d("1000"), d("5"), d("10000"), d("10"), null, null, 3600, "REEVALUATE",
                d("49900"), d("50000"), d("5"), d("10"), UUID.randomUUID(), 1L,
                Instant.now(), Instant.now().plusSeconds(30), null, List.of()
        );

        // Ask > limit -> WAIT / NOT_MARKETABLE
        var waitNotMarketable = LimitDecision.evaluate(
                limitPlan, d("50000"), d("50010"), d("10"), d("10"), d("0.1"), d("0.10")
        );
        assertEquals("WAIT", waitNotMarketable.action());
        assertEquals("NOT_MARKETABLE", waitNotMarketable.reason());

        // Ask <= limit ama slippage sonrası adverse fiyat > limit -> WAIT / ADVERSE_PRICE_OUTSIDE_LIMIT
        var waitSlippage = LimitDecision.evaluate(
                limitPlan, d("49990"), d("49990"), d("10"), d("10"), d("0.1"), d("0.10")
        );
        assertEquals("WAIT", waitSlippage.action());
        assertEquals("ADVERSE_PRICE_OUTSIDE_LIMIT", waitSlippage.reason());

        // Likidite yetersiz (0.2 > 1.0 * 0.10) -> REJECT / LIQUIDITY_LIMIT
        var rejectLiquidity = LimitDecision.evaluate(
                limitPlan, d("49900"), d("49900"), d("1"), d("1"), d("0.1"), d("0.10")
        );
        assertEquals("REJECT", rejectLiquidity.action());
        assertEquals("LIQUIDITY_LIMIT", rejectLiquidity.reason());

        // Uygun fiyat ve likidite -> FILL
        var fill = LimitDecision.evaluate(
                limitPlan, d("49900"), d("49900"), d("10"), d("10"), d("0.1"), d("0.10")
        );
        assertEquals("FILL", fill.action());
        assertNull(fill.reason());
        assertEquals(0, d("49949.9").compareTo(fill.price()));
    }

    @Test
    void fillAccountingProducesBalancedJournalsForOpeningAndClosing() {
        UUID session = UUID.randomUUID();
        UUID available = UUID.randomUUID();
        UUID isolated = UUID.randomUUID();
        UUID fees = UUID.randomUUID();
        UUID pnlPool = UUID.randomUUID();
        UUID insurance = UUID.randomUUID();

        var open = Finance.open(d("1000"), d("10"), d("50000"), d("0.001"), d("0.0005"), d("10000"));
        var openPostings = FillAccounting.opening(available, isolated, fees, open);
        Journal openJournal = new Journal(UUID.randomUUID(), session, "open:1", "h1", "OPEN_FILL", openPostings);
        assertEquals(3, openJournal.postings().size());

        // F03: Long 50500 tam kapama -> released=1000, realized=100, fee=5.05, returned=1094.95
        var normalClose = Finance.close(PositionSide.LONG, d("0.2"), d("0.2"), d("50000"), d("50500"), d("1000"), d("0.0005"));
        var closePostings = FillAccounting.closing(available, isolated, pnlPool, fees, insurance, normalClose);
        Journal closeJournal = new Journal(UUID.randomUUID(), session, "close:1", "h2", "CLOSE_FILL", closePostings);
        assertEquals(5, closeJournal.postings().size());

        // F15: Gap close 44000 -> residual=-204.4, insurance=204.4, available+=0
        var gapClose = Finance.close(PositionSide.LONG, d("0.2"), d("0.2"), d("50000"), d("44000"), d("1000"), d("0.0005"));
        var gapPostings = FillAccounting.closing(available, isolated, pnlPool, fees, insurance, gapClose);
        Journal gapJournal = new Journal(UUID.randomUUID(), session, "close:2", "h3", "INSOLVENT_EXIT", gapPostings);
        assertEquals(0, d("0").compareTo(gapJournal.postings().get(1).amount()));
        assertEquals(0, d("-204.4").compareTo(gapJournal.postings().get(4).amount()));

        // Partial close with negative residual -> forceFullClose=true -> RECOMPUTE_FULL_CLOSE
        var partialNegative = Finance.close(PositionSide.LONG, d("0.2"), d("0.1"), d("50000"), d("40000"), d("1000"), d("0.0005"));
        assertThrows(IllegalStateException.class, ()
                -> FillAccounting.closing(available, isolated, pnlPool, fees, insurance, partialNegative));
    }

    @Test
    void mapperCommandJsonAndApiErrorsRespectContract() throws Exception {
        UUID planId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        String hash = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        OrderMapper mapper = new OrderMapper();

        SubmitOrderCommand cmd = mapper.toCommand(new SubmitOrderRequest(planId, 1, hash));
        assertEquals(planId, cmd.planId());
        assertEquals(1, cmd.revision());
        assertEquals(hash, cmd.planHash());

        assertThrows(IllegalArgumentException.class, () -> new SubmitOrderCommand(planId, 0, hash));
        assertThrows(IllegalArgumentException.class, () -> new SubmitOrderCommand(planId, 1, "bad-hash"));

        Instant acceptedAt = Instant.parse("2026-10-19T12:00:05Z");
        UUID commandId = UUID.randomUUID();
        var response = mapper.toResponse(new SubmitOrder.Accepted(commandId, "ACCEPTED", "/api/v1/sim/commands/" + commandId, acceptedAt));
        assertEquals(commandId, response.commandId());
        assertEquals("ACCEPTED", response.status());

        ObjectMapper objectMapper = new ObjectMapper().registerModule(new DecimalJsonConfig().decimals());
        assertEquals("\"50000.5\"", objectMapper.writeValueAsString(new BigDecimal("50000.500")));

        ApiErrors errors = new ApiErrors();
        assertEquals(404, errors.business(new BusinessException(BusinessException.Code.NOT_FOUND)).getStatusCode().value());
        assertEquals(409, errors.business(new BusinessException(BusinessException.Code.REVALIDATION_REQUIRED)).getStatusCode().value());
        assertEquals(503, errors.business(new BusinessException(BusinessException.Code.STALE_QUOTE)).getStatusCode().value());
        assertTrue(errors.business(new BusinessException(BusinessException.Code.STALE_QUOTE)).getBody().retryable());
        assertEquals(422, errors.business(new BusinessException(BusinessException.Code.SESSION_HALTED)).getStatusCode().value());
        assertEquals(400, errors.legacy(new IllegalArgumentException("INVALID_TICK")).getStatusCode().value());
        assertEquals(409, errors.duplicate().getStatusCode().value());
        assertEquals(500, errors.legacy(new IllegalStateException("UNEXPECTED_INTERNAL")).getStatusCode().value());

        ManualPreviewRequest previewDto = new ManualPreviewRequest(
                "MANUAL", UUID.randomUUID(), UUID.randomUUID(), "LONG", "MARKET",
                "1000", "10", null, "48000", "55000", 3600, "CLOSE_AT_DEADLINE"
        );
        assertEquals("MANUAL", previewDto.source());
        assertEquals(Map.of(), errors.malformed().getBody().details());
    }
}
