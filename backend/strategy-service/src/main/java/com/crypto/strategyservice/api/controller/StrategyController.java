package com.crypto.strategyservice.api.controller;

import com.crypto.strategyservice.application.repository.AnalysisJobRepository;
import com.crypto.strategyservice.application.repository.ScanJobRepository;
import com.crypto.strategyservice.application.service.AnalysisJobTransactions;
import com.crypto.strategyservice.application.service.AnalysisWorker;
import com.crypto.strategyservice.application.service.ScanService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestController
public class StrategyController {

    public record CreateAnalysisRequest(
            @NotNull UUID sessionId,
            @NotNull UUID instrumentId,
            int horizonSeconds,
            Instant asOf
    ) {
    }

    public record CreateScanRequest(
            @NotNull UUID sessionId,
            int horizonSeconds,
            String cursor,
            @Min(1) @Max(200) Integer pageLimit,
            @Min(1) Integer maxAnalysesBudget,
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
            @RequestHeader("X-Owner-Id") UUID ownerId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateAnalysisRequest request
    ) {
        Instant asOf = request.asOf() != null ? request.asOf() : clock.instant();
        UUID analysisId = analysisWorker.enqueue(
                ownerId,
                request.sessionId(),
                request.instrumentId(),
                request.horizonSeconds(),
                asOf,
                idempotencyKey
        );
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "analysisId", analysisId,
                "status", "QUEUED",
                "statusUrl", "/api/v1/strategy/analyses/" + analysisId
        ));
    }

    @GetMapping("/api/v1/strategy/analyses/{id}")
    public ResponseEntity<AnalysisJobRepository.AnalysisView> getAnalysis(
            @RequestHeader("X-Owner-Id") UUID ownerId,
            @PathVariable("id") UUID analysisId
    ) {
        return transactions.findJobView(ownerId, analysisId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/api/v1/strategy/proposals/{id}")
    public ResponseEntity<AnalysisJobRepository.ProposalView> getProposal(
            @RequestHeader("X-Owner-Id") UUID ownerId,
            @PathVariable("id") UUID proposalId,
            @RequestParam(name = "revision", defaultValue = "1") int revision
    ) {
        return transactions.findProposalView(ownerId, proposalId, revision)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/api/v1/strategy/scans")
    public ResponseEntity<ScanJobRepository.ScanJobView> createScan(
            @RequestHeader("X-Owner-Id") UUID ownerId,
            @Valid @RequestBody CreateScanRequest request
    ) {
        int pageLimit = request.pageLimit() != null ? request.pageLimit() : ScanService.MAX_PAGE_LIMIT;
        int budget = request.maxAnalysesBudget() != null ? request.maxAnalysesBudget() : pageLimit;
        Instant asOf = request.asOf() != null ? request.asOf() : clock.instant();

        UUID scanId = scanService.createScan(
                ownerId,
                request.sessionId(),
                request.horizonSeconds(),
                request.cursor()
        );
        var view = scanService.processPage(ownerId, scanId, pageLimit, budget, asOf);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(view);
    }

    @GetMapping("/api/v1/strategy/scans/{id}")
    public ResponseEntity<ScanJobRepository.ScanJobView> getScan(
            @RequestHeader("X-Owner-Id") UUID ownerId,
            @PathVariable("id") UUID scanId
    ) {
        return scanService.findView(ownerId, scanId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/api/v1/strategy/scans/{id}/cancel")
    public ResponseEntity<Map<String, Object>> cancelScan(
            @RequestHeader("X-Owner-Id") UUID ownerId,
            @PathVariable("id") UUID scanId
    ) {
        boolean cancelled = scanService.cancel(ownerId, scanId);
        return ResponseEntity.ok(Map.of(
                "scanId", scanId,
                "cancelled", cancelled
        ));
    }
}

