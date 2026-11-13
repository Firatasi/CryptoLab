package com.crypto.strategyservice.api.controller;

import com.crypto.strategyservice.application.repository.AnalysisJobRepository;
import com.crypto.strategyservice.application.repository.ScanJobRepository;
import com.crypto.strategyservice.application.service.AnalysisJobTransactions;
import com.crypto.strategyservice.application.service.AnalysisWorker;
import com.crypto.strategyservice.application.service.ScanService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestController
public class StrategyController {

    private static final Map<String, UUID> SYMBOL_TO_INSTRUMENT_UUID = Map.of(
            "BTCUSDT", UUID.fromString("11111111-1111-4111-8111-111111111111"),
            "ETHUSDT", UUID.fromString("22222222-2222-4222-8222-222222222222"),
            "SOLUSDT", UUID.fromString("33333333-3333-4333-8333-333333333333"),
            "BNBUSDT", UUID.fromString("44444444-4444-4444-8444-444444444444"),
            "XRPUSDT", UUID.fromString("55555555-5555-4555-8555-555555555555"),
            "DOGEUSDT", UUID.fromString("66666666-6666-4666-8666-666666666666"),
            "ADAUSDT", UUID.fromString("77777777-7777-4777-8777-777777777777"),
            "AVAXUSDT", UUID.fromString("12121212-1212-4212-8212-121212121212"),
            "LINKUSDT", UUID.fromString("13131313-1313-4313-8313-131313131313"),
            "SUIUSDT", UUID.fromString("14141414-1414-4414-8414-141414141414")
    );

    public record CreateAnalysisRequest(
            @NotBlank @Size(max = 128) String sessionId,
            @NotNull @Size(max = 64) String instrumentId,
            @Min(60) @Max(2_592_000) int horizonSeconds,
            Instant asOf,
            @Size(max = 32) String riskLevel
    ) {
    }

    public record CreateScanRequest(
            @NotBlank @Size(max = 128) String sessionId,
            @Min(60) @Max(2_592_000) int horizonSeconds,
            @Size(max = 256) String cursor,
            @Min(1) @Max(200) Integer pageLimit,
            @Min(1) @Max(200) Integer maxAnalysesBudget,
            Instant asOf
    ) {
    }

    private final AnalysisWorker analysisWorker;
    private final AnalysisJobTransactions transactions;
    private final ScanService scanService;
    private final Clock clock;

    public StrategyController(
            AnalysisWorker analysisWorker,
            AnalysisJobTransactions transactions,
            ScanService scanService,
            Clock clock
    ) {
        this.analysisWorker = analysisWorker;
        this.transactions = transactions;
        this.scanService = scanService;
        this.clock = clock;
    }

    @PostMapping("/api/v1/strategy/analyses")
    public ResponseEntity<Map<String, Object>> createAnalysis(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateAnalysisRequest request
    ) {
        UUID ownerId = resolveOwnerId(jwt);
        UUID sessionUuid = resolveSessionUuid(request.sessionId());
        UUID instrumentUuid = resolveInstrumentUuid(request.instrumentId());
        String idem = sanitizeIdempotencyKey(idempotencyKey);
        int horizon = request.horizonSeconds() > 0 ? request.horizonSeconds() : 3600;
        Instant asOf = request.asOf() != null ? request.asOf() : clock.instant();
        String riskLevel = com.crypto.strategyservice.domain.service.AdaptiveNumericPolicy.normalizeRiskLevel(request.riskLevel());
        UUID analysisId = riskLevel == null
                ? analysisWorker.enqueue(ownerId, sessionUuid, instrumentUuid, horizon, asOf, idem)
                : analysisWorker.enqueue(ownerId, sessionUuid, instrumentUuid, horizon, asOf, idem, riskLevel);
        try {
            analysisWorker.processNext();
        } catch (Exception ignored) {
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "analysisId", analysisId,
                "status", "QUEUED",
                "statusUrl", "/api/v1/strategy/analyses/" + analysisId
        ));
    }

    @GetMapping("/api/v1/strategy/analyses/{id}")
    public ResponseEntity<AnalysisJobRepository.AnalysisView> getAnalysis(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") UUID analysisId
    ) {
        UUID ownerId = resolveOwnerId(jwt);
        var viewOpt = transactions.findJobView(ownerId, analysisId);
        if (viewOpt.isPresent() && "QUEUED".equals(viewOpt.get().status())) {
            try {
                analysisWorker.processNext();
                viewOpt = transactions.findJobView(ownerId, analysisId);
            } catch (Exception ignored) {
            }
        }
        return viewOpt
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/api/v1/strategy/proposals/{id}")
    public ResponseEntity<AnalysisJobRepository.ProposalView> getProposal(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") UUID proposalId,
            @RequestParam(name = "revision", defaultValue = "1") int revision
    ) {
        if (revision < 1) {
            throw new IllegalArgumentException("INVALID_REQUEST");
        }
        UUID ownerId = resolveOwnerId(jwt);
        return transactions.findProposalView(ownerId, proposalId, revision)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/api/v1/strategy/scans")
    public ResponseEntity<ScanJobRepository.ScanJobView> createScan(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateScanRequest request
    ) {
        UUID ownerId = resolveOwnerId(jwt);
        int pageLimit = request.pageLimit() != null ? request.pageLimit() : ScanService.MAX_PAGE_LIMIT;
        int budget = request.maxAnalysesBudget() != null ? request.maxAnalysesBudget() : pageLimit;
        int horizon = request.horizonSeconds() > 0 ? request.horizonSeconds() : 3600;
        Instant asOf = request.asOf() != null ? request.asOf() : clock.instant();

        UUID scanId = scanService.createScan(
                ownerId,
                resolveSessionUuid(request.sessionId()),
                horizon,
                request.cursor()
        );
        var view = scanService.processPage(ownerId, scanId, pageLimit, budget, asOf);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(view);
    }

    @GetMapping("/api/v1/strategy/scans/{id}")
    public ResponseEntity<ScanJobRepository.ScanJobView> getScan(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") UUID scanId
    ) {
        UUID ownerId = resolveOwnerId(jwt);
        return scanService.findView(ownerId, scanId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/api/v1/strategy/scans/{id}/cancel")
    public ResponseEntity<Map<String, Object>> cancelScan(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") UUID scanId
    ) {
        UUID ownerId = resolveOwnerId(jwt);
        boolean cancelled = scanService.cancel(ownerId, scanId);
        return ResponseEntity.ok(Map.of(
                "scanId", scanId,
                "cancelled", cancelled
        ));
    }

    private UUID resolveOwnerId(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            throw new AccessDeniedException("UNAUTHORIZED");
        }
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException ex) {
            throw new AccessDeniedException("UNAUTHORIZED");
        }
    }

    private static String sanitizeIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return UUID.randomUUID().toString();
        }
        String trimmed = idempotencyKey.trim();
        if (trimmed.length() > 128) {
            throw new IllegalArgumentException("INVALID_KEY");
        }
        return trimmed;
    }

    private UUID resolveSessionUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("INVALID_SESSION_ID");
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("INVALID_SESSION_ID");
        }
    }

    private UUID resolveInstrumentUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return SYMBOL_TO_INSTRUMENT_UUID.get("BTCUSDT");
        }
        String trimmed = raw.trim();
        if (trimmed.length() > 64) {
            throw new IllegalArgumentException("INVALID_INSTRUMENT_ID");
        }
        UUID mapped = SYMBOL_TO_INSTRUMENT_UUID.get(trimmed.toUpperCase());
        if (mapped != null) {
            return mapped;
        }
        try {
            return UUID.fromString(trimmed);
        } catch (IllegalArgumentException e) {
            String sym = trimmed.toUpperCase();
            if (!sym.matches("^[A-Z0-9_-]{2,24}$")) {
                throw new IllegalArgumentException("INVALID_INSTRUMENT_ID");
            }
            UUID derived = UUID.nameUUIDFromBytes(sym.getBytes(StandardCharsets.UTF_8));
            com.crypto.strategyservice.infrastructure.client.fixture.FixtureMarketCoveragePort.registerSymbol(derived, sym);
            return derived;
        }
    }
}
