package com.crypto.financecore.finance;


import java.math.BigDecimal;

/**
 * Pozisyon yönünü hesaplarda kullanılacak işaretle eşleştirir.
 */
public enum PositionSide {

    LONG(1),
    SHORT(-1);

    private final BigDecimal sign;

    PositionSide(int sign) {
        // Burada tam sayıdan dönüşüm yapılıyor; 0.1 double sorunu yok.
        this.sign = BigDecimal.valueOf(sign);
    }

    public BigDecimal sign() {
        return sign;
    }
}
