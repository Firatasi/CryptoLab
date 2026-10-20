package com.crypto.accountservice.api.dto.response;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
public record TradeHistoryResponse(UUID sessionId,String mode,String currency,List<TradeResponse> items,
        String nextCursor,String coverage,Instant projectionAsOf) {
    public TradeHistoryResponse { items=List.copyOf(items); }
    public record TradeResponse(UUID positionId,UUID sessionId,UUID instrumentId,String symbol,String side,
            String status,Instant openedAt,Instant closedAt,String realizedPnl,String totalFees,
            String signedFunding,String liquidationFees,String netRealizedPnl,String insuranceAdjustment) {}
}

