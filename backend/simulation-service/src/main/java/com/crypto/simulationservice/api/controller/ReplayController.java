package com.crypto.simulationservice.api.controller;

import com.crypto.financecore.finance.Decimal;
import com.crypto.simulationservice.application.replay.ReplayService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestController
public class ReplayController {

    public record CreateReplayRequest(
            @NotNull UUID datasetId,
            @NotNull UUID ruleProfileId,
            @NotNull UUID policyArtifactId,
            long seed,
            @NotBlank String datasetHash,
            @NotBlank String ruleHash,
            @NotBlank String policyHash,
            @NotBlank String tiePolicy,
            @NotBlank String dataLayer,
            @NotBlank String initialDeposit,
            Instant startTime
    ) {
    }

    public record InternalReplayEvalRequest(
            @NotNull UUID scopedOwnerId,
            @NotNull UUID datasetId,
            @NotNull UUID ruleProfileId,
            @NotNull UUID policyArtifactId,
            long seed,
            @NotBlank String datasetHash,
            @NotBlank String ruleHash,
            @NotBlank String policyHash,
            @NotBlank String tiePolicy,
            @NotBlank String dataLayer,
            @NotBlank String initialDeposit,
            Instant startTime
    ) {
    }

    private final ReplayService replayService;
    private final Clock clock;

    public ReplayController(ReplayService replayService, Clock clock) {
        this.replayService = replayService;
        this.clock = clock;
    }

    @PostMapping("/api/v1/sim/replays")
    public ResponseEntity<Map<String, Object>> createReplay(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateReplayRequest body
    ) {
        UUID ownerId = UUID.fromString(jwt.getSubject());
        Instant start = body.startTime() != null ? body.startTime() : clock.instant();
        UUID replayId = replayService.createReplayJob(
                ownerId,
                idempotencyKey,
                new ReplayService.ReplaySpec(
                        body.datasetId(),
                        body.ruleProfileId(),
                        body.policyArtifactId(),
                        body.seed(),
                        body.datasetHash(),
                        body.ruleHash(),
                        body.policyHash(),
                        body.tiePolicy(),
                        body.dataLayer(),
                        Decimal.parse(body.initialDeposit()),
                        start
                )
        );
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "replayId", replayId,
                "status", "QUEUED",
                "statusUrl", "/api/v1/sim/replays/" + replayId
        ));
    }

    @PostMapping("/internal/sim/replay-evaluations")
    public ResponseEntity<Map<String, Object>> createInternalEvaluation(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody InternalReplayEvalRequest body
    ) {
        Instant start = body.startTime() != null ? body.startTime() : clock.instant();
        UUID replayId = replayService.createReplayJob(
                body.scopedOwnerId(),
                idempotencyKey,
                new ReplayService.ReplaySpec(
                        body.datasetId(),
                        body.ruleProfileId(),
                        body.policyArtifactId(),
                        body.seed(),
                        body.datasetHash(),
                        body.ruleHash(),
                        body.policyHash(),
                        body.tiePolicy(),
                        body.dataLayer(),
                        Decimal.parse(body.initialDeposit()),
                        start
                )
        );
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "replayId", replayId,
                "status", "QUEUED"
        ));
    }

    @GetMapping("/api/v1/sim/replays/{id}")
    public ResponseEntity<ReplayService.ReplayResultView> getReplay(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") UUID replayId
    ) {
        UUID ownerId = UUID.fromString(jwt.getSubject());
        return replayService.findReplayView(ownerId, replayId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/api/v1/sim/replays/{id}/cancel")
    public ResponseEntity<Map<String, Object>> cancelReplay(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") UUID replayId
    ) {
        UUID ownerId = UUID.fromString(jwt.getSubject());
        boolean cancelled = replayService.cancelReplay(ownerId, replayId, clock.instant());
        return ResponseEntity.ok(Map.of(
                "replayId", replayId,
                "cancelled", cancelled
        ));
    }

    @GetMapping(value = "/api/v1/sim/replays/{id}/csv", produces = "text/csv")
    public ResponseEntity<String> downloadCsv(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") UUID replayId
    ) {
        UUID ownerId = UUID.fromString(jwt.getSubject());
        String csv = replayService.exportCsv(ownerId, replayId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv);
    }
}
