package com.crypto.accountservice.domain.model;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
public record TradeHistory(UUID ownerId, UUID sessionId, String mode, String currency,
        List<Trade> items, String nextCursor, AccountSummary.Coverage coverage, Instant projectionAsOf) {
    public TradeHistory { items = items == null ? List.of() : List.copyOf(items); }
    public record Trade(UUID positionId, UUID sessionId, UUID instrumentId, String symbol,
            String side, String status, Instant openedAt, Instant closedAt,
            String realizedPnl, String totalFees, String signedFunding,
            String liquidationFees, String netRealizedPnl, String insuranceAdjustment) {}
    // Bir sayfa = toplam rapor değildir. nextCursor opaktır, token içermez.
}

