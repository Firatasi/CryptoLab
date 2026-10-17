package com.crypto.financecore.finance;


import java.math.BigDecimal;

/**
 * Negatif olmayan para tutarını temsil eder.
 * Veritabanı entity'si değildir; yalnızca değer ve doğrulama taşır.
 */
public record Money(BigDecimal value) {

    // Nesne oluşturulurken çalışır; null ve negatif tutarı reddeder.
    public Money {
        Decimal.nonNegative(value);
    }

    // Metni önce ortak ondalık kurallarına göre doğrular.
    public static Money of(String value) {
        return new Money(Decimal.parse(value));
    }

    // Örneğin 100.000 değerini "100" olarak gösterir.
    @Override
    public String toString() {
        return Decimal.wire(value);
    }
}
