package com.crypto.financecore.finance;


import java.math.BigDecimal;

/**
 * Sıfırdan büyük işlem miktarını temsil eder.
 * Örneğin 0.2 BTC miktarı; fiyat veya para tutarı değildir.
 */
public record Quantity(BigDecimal value) {

    // Sıfır miktarlı işlem oluşturulmasını da engeller.
    public Quantity {
        Decimal.positive(value);
    }

    public static Quantity of(String value) {
        return new Quantity(Decimal.parse(value));
    }

    @Override
    public String toString() {
        return Decimal.wire(value);
    }
}
