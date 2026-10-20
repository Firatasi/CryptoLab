package com.crypto.accountservice.api.controller;
import com.crypto.accountservice.application.service.AccountQueryService;
import com.crypto.accountservice.api.mapper.AccountMapper;
import com.crypto.accountservice.api.dto.response.AccountSummaryResponse;
import com.crypto.accountservice.api.dto.response.TradeHistoryResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
@RestController
@RequestMapping("/api/v1/account/me")
public class AccountController {
    private final AccountQueryService queries;
    private final AccountMapper mapper;
    public AccountController(AccountQueryService queries,AccountMapper mapper) { this.queries=queries;this.mapper=mapper; }
    @GetMapping("/summary")
    public AccountSummaryResponse summary(@AuthenticationPrincipal Jwt jwt,@RequestParam(required=false) UUID sessionId) {
        return mapper.toResponse(queries.summary(UUID.fromString(jwt.getSubject()),jwt.getTokenValue(),sessionId));
    }
    @GetMapping("/trades")
    public TradeHistoryResponse trades(@AuthenticationPrincipal Jwt jwt,@RequestParam(required=false) UUID sessionId,
            @RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int limit) {
        return mapper.toResponse(queries.trades(UUID.fromString(jwt.getSubject()),jwt.getTokenValue(),sessionId,cursor,limit));
    }
}

