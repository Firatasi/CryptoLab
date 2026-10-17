package com.crypto.financecore.finance;

import java.math.BigDecimal;

/**
 * Bir sürdürme teminatı kademesini temsil eder.
 *
 * floor: Kademenin dahil olan alt sınırı.
 * cap: Kademenin hariç olan üst sınırı; null ise üst sınır yoktur.
 * rate: Bu kademede uygulanacak sürdürme teminatı oranı.
 * amount: Teminat hesabında çıkarılacak sabit düzeltme tutarı.
 */
public record MaintenanceTier(
        BigDecimal floor,
        BigDecimal cap,
        BigDecimal rate,
        BigDecimal amount
) {

    public MaintenanceTier {
        // Alt sınır, oran ve düzeltme tutarı negatif olamaz.
        Decimal.nonNegative(floor);
        Decimal.nonNegative(rate);
        Decimal.nonNegative(amount);

        // Oran 1'den (%100) küçük olmalıdır.
        // Üst sınır varsa alt sınırdan büyük olmalıdır.
        if (rate.compareTo(BigDecimal.ONE) >= 0
                || (cap != null && cap.compareTo(floor) <= 0)) {
            throw new IllegalArgumentException("INVALID_TIER");
        }
    }

    public boolean contains(BigDecimal notional) {
        // [floor, cap): alt sınır dahil, üst sınır hariç.
        // Böylece ortak sınır iki komşu kademeye birden girmez.
        return notional.compareTo(floor) >= 0
                && (cap == null || notional.compareTo(cap) < 0);
    }
}
