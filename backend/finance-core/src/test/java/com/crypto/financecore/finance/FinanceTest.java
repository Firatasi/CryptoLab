package com.crypto.financecore.finance;


import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static com.crypto.financecore.finance.PositionSide.LONG;
import static com.crypto.financecore.finance.PositionSide.SHORT;
import static org.junit.jupiter.api.Assertions.*;

class FinanceTest {

    private static BigDecimal d(String value) {
        return Decimal.parse(value);
    }

    // compareTo kullanıyoruz: 5.0 ve 5.00 sayısal olarak eşittir.
    private static void eq(String expected, BigDecimal actual) {
        assertEquals(
                0,
                d(expected).compareTo(actual),
                () -> "Beklenen: " + expected + ", bulunan: " + actual
        );
    }

    // Sonsuz ondalıklı sonuçlarda açık bir hata toleransı kullanıyoruz.
    private static void near(String expected, BigDecimal actual) {
        BigDecimal difference = d(expected).subtract(actual).abs();

        assertTrue(
                difference.compareTo(d("0.000001")) <= 0,
                () -> "Beklenen yaklaşık: " + expected + ", bulunan: " + actual
        );
    }

    private final List<MaintenanceTier> tiers = List.of(
            new MaintenanceTier(d("0"), null, d("0.004"), d("0"))
    );

    @Test
    void f01ToF04_openingPnlAndClosing() {
        var opening = Finance.open(
                d("1000"), d("10"), d("50000"),
                d("0.001"), d("0.0005"), d("10000")
        );

        eq("0.2", opening.quantity());
        eq("10000", opening.notional());
        eq("1000", opening.margin());
        eq("5", opening.fee());
        eq("1005", opening.requiredCash());

        BigDecimal available = d("10000").subtract(opening.requiredCash());

        eq("8995", available);
        eq("9995", available.add(opening.margin()));

        BigDecimal unrealized = Finance.pnl(
                LONG, d("0.2"), d("50000"), d("50500")
        );

        eq("100", unrealized);
        eq("10095", available.add(opening.margin()).add(unrealized));

        var longClosing = Finance.close(
                LONG, d("0.2"), d("0.2"),
                d("50000"), d("50500"), d("1000"), d("0.0005")
        );

        eq("100", longClosing.realized());
        eq("5.05", longClosing.fee());
        eq("10089.95", available.add(longClosing.returnedCash()));

        var shortClosing = Finance.close(
                SHORT, d("0.2"), d("0.2"),
                d("50000"), d("49500"), d("1000"), d("0.0005")
        );

        eq("100", shortClosing.realized());
        eq("4.95", shortClosing.fee());
        eq("10090.05", available.add(shortClosing.returnedCash()));
    }

    @Test
    void f05ToF07_fundingUsesAvailableBalanceFirst() {
        var longFunding = Finance.funding(
                LONG, d("0.2"), d("50000"), d("0.0001"),
                d("8995"), d("1000")
        );

        eq("-1", longFunding.signedFunding());
        eq("8994", longFunding.availableAfter());
        eq("1000", longFunding.isolatedAfter());

        var shortFunding = Finance.funding(
                SHORT, d("0.2"), d("50000"), d("0.0001"),
                d("8995"), d("1000")
        );

        eq("1", shortFunding.signedFunding());
        eq("8996", shortFunding.availableAfter());
        eq("1000", shortFunding.isolatedAfter());

        var lowBalance = Finance.funding(
                LONG, d("0.2"), d("50000"), d("0.0001"),
                d("0.25"), d("1000")
        );

        eq("0", lowBalance.availableAfter());
        eq("999.25", lowBalance.isolatedAfter());
    }

    @Test
    void f08ToF11_liquidationAndNegativeResidual() {
        near(
                "45180.722891",
                Finance.liquidation(
                        LONG, d("0.2"), d("50000"), d("1000"), tiers
                ).orElseThrow()
        );

        near(
                "54780.876494",
                Finance.liquidation(
                        SHORT, d("0.2"), d("50000"), d("1000"), tiers
                ).orElseThrow()
        );

        assertTrue(Finance.liquidates(
                LONG, d("0.2"), d("50000"), d("45000"), d("1000"), tiers
        ));

        eq("36", Finance.maintenance(d("0.2"), d("45000"), tiers));

        var closing = Finance.close(
                LONG, d("0.2"), d("0.2"),
                d("50000"), d("44000"), d("1000"), d("0")
        );

        eq("-1200", closing.realized());
        eq("-200", closing.rawResidual());
        eq("0", closing.returnedCash());
        eq("200", closing.insurance());
    }

    @Test
    void f12ToF15_partialCloseBudgetAndInsurance() {
        var first = Finance.close(
                LONG, d("0.2"), d("0.1"),
                d("50000"), d("50500"), d("1000"), d("0.0005")
        );

        eq("500", first.released());
        eq("50", first.realized());
        eq("2.525", first.fee());
        assertFalse(first.forceFullClose());

        BigDecimal available = d("8995").add(first.returnedCash());
        eq("9542.475", available);

        var last = Finance.close(
                LONG, d("0.1"), d("0.1"),
                d("50000"), d("49500"), d("500"), d("0.0005")
        );

        eq("500", last.released());
        eq("9990", available.add(last.returnedCash()));

        assertThrows(IllegalArgumentException.class, () -> Finance.open(
                d("1000"), d("10"), d("50000"),
                d("0.001"), d("0.0005"), d("1000")
        ));

        var gap = Finance.close(
                LONG, d("0.2"), d("0.2"),
                d("50000"), d("44000"), d("1000"), d("0.0005")
        );

        eq("-204.4", gap.rawResidual());
        eq("204.4", gap.insurance());
        eq("0", gap.returnedCash());
        eq("4.4", gap.fee());
    }

    @Test
    void tierBoundaryAndLiquidationCandidate() {
        var lower = new MaintenanceTier(
                d("0"), d("5000"), d("0.004"), d("0")
        );
        var upper = new MaintenanceTier(
                d("5000"), null, d("0.005"), d("5")
        );
        var tiered = List.of(lower, upper);

        assertTrue(lower.contains(d("4999.99")));
        assertFalse(lower.contains(d("5000")));
        assertTrue(upper.contains(d("5000")));

        eq("20", Finance.maintenance(d("1"), d("5000"), tiered));

        BigDecimal candidate = Finance.liquidation(
                LONG, d("0.2"), d("50000"), d("1000"), tiered
        ).orElseThrow();

        near("45201.005025", candidate);
        assertTrue(upper.contains(d("0.2").multiply(candidate)));
    }

    @Test
    void quantityAndPriceRounding() {
        eq("0.2", Finance.floorStep(d("0.209"), d("0.01")));
        eq("0", Finance.floorStep(d("0.009"), d("0.01")));

        eq("100.1", Finance.marketPrice(
                true, d("100"), d("100.01"), d("0.1"), d("0")
        ));

        eq("99.9", Finance.marketPrice(
                false, d("99.99"), d("100"), d("0.1"), d("0")
        ));

        // 10 bps = %0,1: alış fiyatı artar, satış fiyatı azalır.
        eq("100.1", Finance.marketPrice(
                true, d("100"), d("100"), d("0.01"), d("10")
        ));

        eq("99.9", Finance.marketPrice(
                false, d("100"), d("100"), d("0.01"), d("10")
        ));
    }

    @Test
    void allInQuantityIncludesOpeningFee() {
        BigDecimal quantity = Finance.allInQuantity(
                d("1000"), d("50000"), d("10"), d("0.0005"), d("0.001")
        );

        eq("0.199", quantity);

        // 0.199 × 50000 / 10 + 0.199 × 50000 × 0.0005
        BigDecimal required = quantity.multiply(d("5000"))
                .add(quantity.multiply(d("25")));

        eq("999.975", required);
        assertTrue(required.compareTo(d("1000")) <= 0);

        BigDecimal nextLotCost = quantity.add(d("0.001")).multiply(d("5025"));
        assertTrue(nextLotCost.compareTo(d("1000")) > 0);
    }

    @Test
    void negativePartialResidualRequiresFullClose() {
        var partial = Finance.close(
                LONG, d("0.2"), d("0.1"),
                d("50000"), d("40000"), d("1000"), d("0.0005")
        );

        eq("-502", partial.rawResidual());
        eq("0", partial.insurance());
        assertTrue(partial.forceFullClose());
    }

    @Test
    void fundingCanReverseDirectionAndMakeIsolatedNegative() {
        var received = Finance.funding(
                LONG, d("0.2"), d("50000"), d("-0.0001"),
                d("0"), d("1000")
        );

        eq("1", received.signedFunding());
        eq("1", received.availableAfter());

        var depleted = Finance.funding(
                LONG, d("0.2"), d("50000"), d("0.0001"),
                d("0"), d("0.25")
        );

        eq("0", depleted.availableAfter());
        eq("-0.75", depleted.isolatedAfter());
    }

    @Test
    void longAndShortPnlCancelForSamePrices() {
        BigDecimal longPnl = Finance.pnl(
                LONG, d("0.2"), d("50000"), d("50500")
        );
        BigDecimal shortPnl = Finance.pnl(
                SHORT, d("0.2"), d("50000"), d("50500")
        );

        eq("100", longPnl);
        eq("-100", shortPnl);
        eq("0", longPnl.add(shortPnl));
    }

    @Test
    void invalidInputsAreRejected() {
        for (String invalid : List.of(
                "NaN", "Infinity", "1e3", "01", "0,1",
                "0.0000000000000000001", "100000000000000000000"
        )) {
            assertThrows(IllegalArgumentException.class, () -> d(invalid));
        }

        assertThrows(IllegalArgumentException.class, () -> Decimal.parse(null));
        assertThrows(IllegalArgumentException.class, () -> Quantity.of("0"));
        assertThrows(IllegalArgumentException.class, () -> Quantity.of("-1"));
        assertThrows(IllegalArgumentException.class, () -> Money.of("-1"));

        assertThrows(IllegalArgumentException.class, () ->
                Finance.floorStep(d("1"), d("0")));

        assertThrows(IllegalArgumentException.class, () ->
                Finance.pnl(LONG, d("1"), d("0"), d("100")));

        assertThrows(IllegalArgumentException.class, () -> Finance.close(
                LONG, d("0.2"), d("0.3"),
                d("50000"), d("50500"), d("1000"), d("0.0005")
        ));

        assertThrows(IllegalArgumentException.class, () -> Finance.open(
                d("1000"), d("0.5"), d("50000"),
                d("0.001"), d("0.0005"), d("10000")
        ));

        assertThrows(IllegalArgumentException.class, () -> Finance.marketPrice(
                true, d("101"), d("100"), d("0.1"), d("0")
        ));
    }

    @Test
    void cashRoundingUsesHalfEven() {
        eq("1.12345678", Decimal.cash(d("1.123456785")));
        eq("1.12345680", Decimal.cash(d("1.123456795")));
        assertEquals("5", Decimal.wire(d("5.000")));
    }
}