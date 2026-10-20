package com.crypto.simulationservice.domain.model;

import java.time.Instant;
import java.math.BigDecimal;

/**
 * Sorumluluk: İş verisini ve kendi değişmez kurallarını temsil eder. Okuma
 * ipucu: Modelin HTTP veya veritabanı biçimine bağımlı olup olmadığını kontrol
 * et.
 */
public final class FundingBoundary {

    private FundingBoundary() {
    }

    public static boolean eligible(Instant openedAt, Instant closedAt, Instant boundary) {
        // Tam boundary'de açılan dahil değil; o anda kapanan eski pozisyon dahil.
        return openedAt.isBefore(boundary) && (closedAt == null || !closedAt.isBefore(boundary));
    }

    public record Allocation(BigDecimal availableDelta, BigDecimal isolatedDelta, BigDecimal poolDelta) {}

    public static Allocation allocate(BigDecimal signed, BigDecimal available, boolean closed) {
        if (signed.signum() >= 0) {
            return new Allocation(signed, BigDecimal.ZERO, signed.negate());
        }
        BigDecimal paid = available.min(signed.negate());
        if (closed) {
            // Geç settlement kapalı pozisyonun eski B'sini hayali olarak açmaz.
            // Kalan borç için açık realized-adjustment/insurance muhasebesi uygulanmalıdır.
            return new Allocation(paid.negate(), BigDecimal.ZERO, paid);
        }
        BigDecimal remainder = signed.negate().subtract(paid);
        return new Allocation(paid.negate(), remainder.negate(), signed.negate());
    }
}
