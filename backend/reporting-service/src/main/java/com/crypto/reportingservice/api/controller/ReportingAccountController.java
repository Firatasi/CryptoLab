package com.crypto.reportingservice.api.controller;

import com.crypto.reportingservice.infrastructure.client.SimulationProjectionSyncService;
import com.crypto.reportingservice.infrastructure.repository.AccountSummaryQueryRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/reports/me")
public class ReportingAccountController {

    private final AccountSummaryQueryRepository repository;
    private final SimulationProjectionSyncService syncService;

    public ReportingAccountController(
            AccountSummaryQueryRepository repository,
            SimulationProjectionSyncService syncService) {
        this.repository = repository;
        this.syncService = syncService;
    }

    @GetMapping("/account-summary")
    public AccountSummaryQueryRepository.SummaryView summary(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) UUID sessionId) {
        UUID subject = UUID.fromString(jwt.getSubject());
        syncService.syncIfAvailable(jwt.getTokenValue(), subject, sessionId);
        return repository.querySummary(subject, sessionId);
    }

    @GetMapping("/trade-history")
    public AccountSummaryQueryRepository.TradeHistoryView tradeHistory(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) UUID sessionId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        UUID subject = UUID.fromString(jwt.getSubject());
        syncService.syncIfAvailable(jwt.getTokenValue(), subject, sessionId);
        return repository.queryTradeHistory(subject, sessionId, cursor, limit);
    }
}

