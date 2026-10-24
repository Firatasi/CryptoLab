package com.crypto.accountservice.api.dto.response;
import java.time.Instant;
import java.util.UUID;
public record AccountSummaryResponse(UUID sessionId,String mode,String currency,
        String realizedPnl,String totalFees,String signedFunding,String liquidationFees,
        String netRealizedPnl,String unrealizedPnl,String totalNetPnl,String insuranceAdjustment,
        Long closedTradeCount,String coverage,Instant projectionAsOf,Long lagMs) {}

