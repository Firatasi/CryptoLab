package com.crypto.accountservice.application.port;
import com.crypto.accountservice.domain.model.AccountSummary;
import com.crypto.accountservice.domain.model.TradeHistory;
import java.util.UUID;
public interface AccountReportReader {
    AccountSummary summary(String bearer, UUID session);
    TradeHistory trades(String bearer, UUID session, String cursor, int limit);
    // Bearer yalnız bu senkron istek kapsamında aktarılır; DB/cache/async job'a yazılmaz.
}

