package com.crypto.accountservice.api.mapper;
import com.crypto.accountservice.domain.model.AccountSummary;
import com.crypto.accountservice.domain.model.TradeHistory;
import com.crypto.accountservice.api.dto.response.AccountSummaryResponse;
import com.crypto.accountservice.api.dto.response.TradeHistoryResponse;
import org.springframework.stereotype.Component;
@Component
public class AccountMapper {
    public AccountSummaryResponse toResponse(AccountSummary s) {
        return new AccountSummaryResponse(s.sessionId(),s.mode(),s.currency(),s.realizedPnl(),s.totalFees(),
                s.signedFunding(),s.liquidationFees(),s.netRealizedPnl(),s.unrealizedPnl(),s.totalNetPnl(),
                s.insuranceAdjustment(),s.closedTradeCount(),s.coverage().name(),s.projectionAsOf(),s.lagMs());
    }
    public TradeHistoryResponse toResponse(TradeHistory p) {
        var items=p.items().stream().map(t -> new TradeHistoryResponse.TradeResponse(t.positionId(),t.sessionId(),
                t.instrumentId(),t.symbol(),t.side(),t.status(),t.openedAt(),t.closedAt(),t.realizedPnl(),
                t.totalFees(),t.signedFunding(),t.liquidationFees(),t.netRealizedPnl(),t.insuranceAdjustment())).toList();
        return new TradeHistoryResponse(p.sessionId(),p.mode(),p.currency(),items,p.nextCursor(),
                p.coverage().name(),p.projectionAsOf());
    }
}

