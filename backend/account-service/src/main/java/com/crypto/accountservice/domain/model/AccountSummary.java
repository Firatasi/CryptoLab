package com.crypto.accountservice.domain.model;
import java.time.Instant;
import java.util.UUID;
public record AccountSummary(UUID ownerId, UUID sessionId, String mode, String currency,
        String realizedPnl, String totalFees, String signedFunding, String liquidationFees,
        String netRealizedPnl, String unrealizedPnl, String totalNetPnl,
        String insuranceAdjustment, Long closedTradeCount, Coverage coverage,
        Instant projectionAsOf, Long lagMs) {
    public enum Coverage { READY, STALE, BUILDING }
    // Decimal'lar wire sözleşmesinde string. Hesap Reporting/Simulation sınırında yapılır.
    // BUILDING ise toplam alanları null olabilir; kullanıcıya sahte 0 dönülmez.
}

