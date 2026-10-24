package com.crypto.financecore.finance;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class DecimalTest {

    @Test
    void stringAndDoubleDifference() {
        BigDecimal fromString = Decimal.parse("0.1");

        // Yalnızca farkı göstermek için double kullanıyoruz.
        // Finans hesaplarında bu şekilde oluşturmayacağız.
        BigDecimal fromDouble = new BigDecimal(0.1);

        System.out.println("String: " + fromString.toPlainString());
        System.out.println("double: " + fromDouble.toPlainString());

        // double üzerinden gelen değer tam olarak 0.1 değildir.
        assertNotEquals(0, fromString.compareTo(fromDouble));
    }

    @Test
    void precisionAndScaleDifference() {
        BigDecimal value = Decimal.parse("123.4500");

        // Toplam anlamlı basamak: 1, 2, 3, 4, 5, 0, 0.
        assertEquals(7, value.precision());

        // Virgülden sonraki basamaklar: 4, 5, 0, 0.
        assertEquals(4, value.scale());
    }

    @Test
    void forEquals() {
        BigDecimal first = Decimal.parse("5.0");
        BigDecimal second = Decimal.parse("5.00");

        // equals hem değeri hem scale'i karşılaştırır.
        assertFalse(first.equals(second));

        // compareTo sayısal değerler eşitse 0 döndürür.
        assertEquals(0, first.compareTo(second));

        // Dışarı verilen metindeki gereksiz sıfırlar temizlenir.
        assertEquals("5", Decimal.wire(second));
    }
}