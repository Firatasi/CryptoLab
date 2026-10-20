package com.crypto.accountservice.application.service;
import com.crypto.accountservice.application.port.AccountReportReader;
import com.crypto.accountservice.domain.exception.AccountFailure;
import com.crypto.accountservice.domain.model.AccountSummary;
import com.crypto.accountservice.domain.model.TradeHistory;
import org.springframework.stereotype.Service;
import java.util.Objects;
import java.util.UUID;
import static com.crypto.accountservice.domain.exception.AccountFailure.Code.*;
@Service
public class AccountQueryService {
    private final AccountReportReader reports;
    public AccountQueryService(AccountReportReader reports) { this.reports = reports; }
    public AccountSummary summary(UUID owner, String bearer, UUID session) {
        var result = reports.summary(bearer, session);
        if (result == null) throw new AccountFailure(INVALID_REPORT);
        checkScope(owner,session,result.ownerId(),result.sessionId(),result.mode(),result.currency());
        if (result.coverage() == null) throw new AccountFailure(INVALID_REPORT);
        var amounts = new String[] { result.realizedPnl(), result.totalFees(), result.signedFunding(),
                result.liquidationFees(), result.netRealizedPnl(), result.unrealizedPnl(),
                result.totalNetPnl(), result.insuranceAdjustment() };
        boolean building = result.coverage() == AccountSummary.Coverage.BUILDING;
        for (String amount : amounts) {
            if (building ? amount != null : amount == null || !amount.matches("-?[0-9]+([.][0-9]+)?"))
                throw new AccountFailure(INVALID_REPORT);
        }
        if (building ? result.closedTradeCount() != null : result.closedTradeCount() == null
                || result.closedTradeCount() < 0 || result.projectionAsOf() == null)
            throw new AccountFailure(INVALID_REPORT);
        return result;
    }
    public TradeHistory trades(UUID owner, String bearer, UUID session, String cursor, int limit) {
        if (limit < 1 || limit > 100 || (cursor != null &&
                (cursor.length() > 2048 || !cursor.matches("[A-Za-z0-9_.-]+")))) {
            throw new AccountFailure(INVALID_QUERY);
        }
        var page = reports.trades(bearer, session, cursor, limit);
        if (page == null) throw new AccountFailure(INVALID_REPORT);
        checkScope(owner,session,page.ownerId(),page.sessionId(),page.mode(),page.currency());
        if (page.coverage()==null || page.items().size()>limit) throw new AccountFailure(INVALID_REPORT);
        if (session!=null && page.items().stream().anyMatch(t -> !session.equals(t.sessionId())))
            throw new AccountFailure(INVALID_REPORT);
        return page;
    }
    private void checkScope(UUID expectedOwner,UUID expectedSession,UUID owner,UUID session,String mode,String currency) {
        // Downstream cevabı bile kendi istek kapsamımızla eşleşmeli.
        if (!Objects.equals(expectedOwner,owner) || !Objects.equals(expectedSession,session)
                || !"LIVE_PAPER".equals(mode) || !"USDT".equals(currency)) throw new AccountFailure(INVALID_REPORT);
    }
}

