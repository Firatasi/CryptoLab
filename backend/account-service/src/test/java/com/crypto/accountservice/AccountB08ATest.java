package com.crypto.accountservice;

import com.crypto.accountservice.api.controller.AccountController;
import com.crypto.accountservice.api.exception.AccountErrors;
import com.crypto.accountservice.application.port.AccountReportReader;
import com.crypto.accountservice.application.service.AccountQueryService;
import com.crypto.accountservice.domain.exception.AccountFailure;
import com.crypto.accountservice.domain.model.AccountSummary;
import com.crypto.accountservice.domain.model.TradeHistory;
import com.crypto.accountservice.infrastructure.client.BoundedBody;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class AccountB08ATest {

    @Autowired
    AccountErrors accountErrors;

    @Test
    void accountQueryService_validatesReadyAndBuildingResponsesAndRejectsScopeMismatch() {
        UUID owner = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        Instant asOf = Instant.parse("2026-02-01T15:00:00Z");

        AccountReportReader fakeReader = new AccountReportReader() {
            @Override
            public AccountSummary summary(String bearer, UUID s) {
                if ("building-token".equals(bearer)) {
                    return new AccountSummary(owner, s, "LIVE_PAPER", "USDT",
                            null, null, null, null, null, null, null, null,
                            null, AccountSummary.Coverage.BUILDING, null, null);
                }
                if ("down-token".equals(bearer)) {
                    throw new AccountFailure(AccountFailure.Code.REPORT_UNAVAILABLE);
                }
                return new AccountSummary(owner, s, "LIVE_PAPER", "USDT",
                        "100", "10.05", "-1", "0", "88.95", "-20", "68.95", "5",
                        1L, AccountSummary.Coverage.READY, asOf, 0L);
            }

            @Override
            public TradeHistory trades(String bearer, UUID s, String cursor, int limit) {
                return new TradeHistory(owner, s, "LIVE_PAPER", "USDT", List.of(), null, AccountSummary.Coverage.READY, asOf);
            }
        };

        var service = new AccountQueryService(fakeReader);
        var mapper = new com.crypto.accountservice.api.mapper.AccountMapper();
        var controller = new AccountController(service, mapper);

        Jwt jwt = Jwt.withTokenValue("ready-token")
                .header("alg", "RS256")
                .subject(owner.toString())
                .build();

        var readyResp = controller.summary(jwt, session);
        assertEquals("READY", readyResp.coverage());
        assertEquals("88.95", readyResp.netRealizedPnl());
        assertEquals("68.95", readyResp.totalNetPnl());
        assertEquals("5", readyResp.insuranceAdjustment());

        // BUILDING durumunda null değerler sahte 0'a çevrilmez
        Jwt buildingJwt = Jwt.withTokenValue("building-token")
                .header("alg", "RS256")
                .subject(owner.toString())
                .build();
        var buildingResp = controller.summary(buildingJwt, session);
        assertEquals("BUILDING", buildingResp.coverage());
        assertNull(buildingResp.totalNetPnl());
        assertNull(buildingResp.closedTradeCount());

        // Reporting kapalıyken 503 REPORT_UNAVAILABLE döner (sahte sıfır dönmez)
        Jwt downJwt = Jwt.withTokenValue("down-token")
                .header("alg", "RS256")
                .subject(owner.toString())
                .build();
        var ex503 = assertThrows(AccountFailure.class, () -> controller.summary(downJwt, session));
        assertEquals(503, accountErrors.business(ex503).getStatusCode().value());

        // Geçersiz limit (0 ve 101) veya bozuk cursor -> 400 INVALID_QUERY
        var ex400 = assertThrows(AccountFailure.class, () -> controller.trades(jwt, session, null, 0));
        assertEquals(400, accountErrors.business(ex400).getStatusCode().value());
        var exCursor = assertThrows(AccountFailure.class, () -> controller.trades(jwt, session, "bad cursor!", 20));
        assertEquals(400, accountErrors.business(exCursor).getStatusCode().value());

        // Başka owner'ın verisi dönerse checkScope 502 INVALID_REPORT fırlatır
        Jwt wrongOwnerJwt = Jwt.withTokenValue("ready-token")
                .header("alg", "RS256")
                .subject(UUID.randomUUID().toString())
                .build();
        var ex502 = assertThrows(AccountFailure.class, () -> controller.summary(wrongOwnerJwt, session));
        assertEquals(502, accountErrors.business(ex502).getStatusCode().value());
    }

    @Test
    void boundedBody_rejectsOversizedPayload() {
        BoundedBody body = new BoundedBody(10);
        body.onSubscribe(new Flow.Subscription() {
            @Override public void request(long n) {}
            @Override public void cancel() {}
        });
        body.onNext(List.of(ByteBuffer.wrap(new byte[11])));
        assertThrows(ExecutionException.class, () -> body.getBody().toCompletableFuture().get());
    }
}

