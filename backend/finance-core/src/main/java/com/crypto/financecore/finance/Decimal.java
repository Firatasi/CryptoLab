package com.crypto.financecore.finance;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Finans hesaplarında kullanılan ortak ondalık sayı kuralları.
 * Spring, veritabanı, saat veya ağ bağlantısına ihtiyaç duymaz.
 */
public final class Decimal {

    // Yalnızca static metotlar kullanılır; nesne oluşturulmasını engeller.
    private Decimal() {
    }

    // Hesaplarda 34 anlamlı basamak hassasiyeti sağlar.
    // Bu, virgülden sonra 34 basamak demek değildir.
    public static final MathContext MC = MathContext.DECIMAL128;

    public static BigDecimal parse(String text) {
        // Sözleşmeye göre bilimsel gösterim, virgül, boşluk ve
        // gereksiz baştaki sıfırlar kabul edilmez.
        if (text == null
                || !text.matches("^-?(0|[1-9][0-9]*)(\\.[0-9]+)?$")) {
            throw new IllegalArgumentException("INVALID_DECIMAL");
        }

        // String üzerinden oluşturmak, double dönüşümündeki
        // ikilik kayan nokta yaklaşımını hesaba taşımamızı önler.
        BigDecimal value = new BigDecimal(text);

        // NUMERIC(38,18) sınırı: en fazla 20 tam, 18 kesir basamağı.
        // Burada DB'ye bağlanmıyoruz; saklama sınırını doğruluyoruz.
        if (value.scale() > 18
                || value.precision() - value.scale() > 20) {
            throw new IllegalArgumentException("DECIMAL_OUT_OF_RANGE");
        }

        return value;
    }

    public static BigDecimal positive(BigDecimal value) {
        // Miktar ve fiyat gibi sıfırdan büyük olması gereken girdiler.
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException("POSITIVE_REQUIRED");
        }

        return value;
    }

    public static BigDecimal nonNegative(BigDecimal value) {
        // Bakiye gibi sıfır olabilen, negatif olamayan girdiler.
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException("NON_NEGATIVE_REQUIRED");
        }

        return value;
    }

    public static BigDecimal cash(BigDecimal value) {
        // Nakit kayıt sınırında 8 kesir basamağına yuvarlar.
        // Ara hesapların her adımında çağrılmaz.
        return value.setScale(8, RoundingMode.HALF_EVEN);
    }

    public static String wire(BigDecimal value) {
        // 5.000 değerini "5" olarak verir.
        // Bilimsel gösterim üretmeden metne dönüştürür.
        return value.stripTrailingZeros().toPlainString();
    }
}