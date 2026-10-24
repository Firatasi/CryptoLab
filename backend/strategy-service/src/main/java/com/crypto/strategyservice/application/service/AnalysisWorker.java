package com.crypto.strategyservice.application.service;

import com.crypto.strategyservice.application.port.ExplanationClient;
import com.crypto.strategyservice.application.port.MarketCoveragePort;
import com.crypto.strategyservice.application.port.ModelClient;
import com.crypto.strategyservice.application.port.SessionContextReader;
import com.crypto.strategyservice.application.repository.AnalysisJobRepository;
import com.crypto.strategyservice.domain.model.PredictionResult;
import com.crypto.strategyservice.domain.service.ExplanationTemplate;
import com.crypto.strategyservice.domain.service.NumericPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class AnalysisWorker {

    private static final Set<Integer> SUPPORTED_HORIZONS = Set.of(60, 900, 3600, 86400, 604800);

    private static final NumericPolicy.Artifact DEFAULT_POLICY_ARTIFACT = new NumericPolicy.Artifact(
            "policy-v1",
            0.01,
            0.95,
            new BigDecimal("2"),
            new BigDecimal("3"),
            new BigDecimal("2"),
            0.50
    );

    private final AnalysisJobTransactions transactions;
    private final ModelClient modelClient;
    private final SessionContextReader sessionContextReader;
    private final MarketCoveragePort marketCoveragePort;
    private final ExplanationClient explanationClient;
    private final Clock clock;
    private final Duration leaseDuration;
    private final Duration proposalTtl;
    private final String policyVersion;

    public AnalysisWorker(
            AnalysisJobTransactions transactions,
            ModelClient modelClient,
            SessionContextReader sessionContextReader,
            MarketCoveragePort marketCoveragePort,
            ExplanationClient explanationClient,
            Clock clock,
            @Value("${strategy.worker.lease-seconds:30}") long leaseSeconds,
            @Value("${strategy.proposal.ttl-seconds:60}") long proposalTtlSeconds,
            @Value("${strategy.policy.version:policy-v1}") String policyVersion
    ) {
        this.transactions = transactions;
        this.modelClient = modelClient;
        this.sessionContextReader = sessionContextReader;
        this.marketCoveragePort = marketCoveragePort;
        this.explanationClient = explanationClient;
        this.clock = clock;
        this.leaseDuration = Duration.ofSeconds(leaseSeconds);
        this.proposalTtl = Duration.ofSeconds(proposalTtlSeconds);
        this.policyVersion = policyVersion;
    }

    public UUID enqueue(
            UUID ownerId,
            UUID sessionId,
            UUID instrumentId,
            int horizonSeconds,
            Instant asOf,
            String idempotencyKey
    ) {
        if (!SUPPORTED_HORIZONS.contains(horizonSeconds)) {
            throw new IllegalArgumentException("INVALID_HORIZON");
        }
        var session = sessionContextReader.findOwnedSession(ownerId, sessionId, instrumentId)
                .orElseThrow(() -> new IllegalStateException("SESSION_NOT_FOUND"));
        if (!"RUNNING".equals(session.status())) {
            throw new IllegalStateException("SESSION_HALTED");
        }
        return transactions.enqueueIdempotent(
                ownerId,
                sessionId,
                instrumentId,
                horizonSeconds,
                asOf,
                idempotencyKey,
                clock.instant()
        );
    }

    public boolean processNext() {
        Instant claimAt = clock.instant();
        var claimedOpt = transactions.claimNext(claimAt, leaseDuration);
        if (claimedOpt.isEmpty()) {
            return false;
        }
        var job = claimedOpt.get();

        // 1. Oturum sahipliği ve durum kontrolü (DB transaction dışında)
        var sessionOpt = sessionContextReader.findOwnedSession(job.ownerId(), job.sessionId(), job.instrumentId());
        if (sessionOpt.isEmpty() || !"RUNNING".equals(sessionOpt.get().status())) {
            transactions.fail(job, "SESSION_NOT_RUNNING", clock.instant());
            return true;
        }
        var session = sessionOpt.get();

        // 2. AsOf ve veri kapsamı (coverage) kontrolü -> eksikse WAITING_DATA ve tekil backfill
        var market = marketCoveragePort.evaluate(job.instrumentId(), job.horizonSeconds(), job.asOf());
        if (!market.sufficientCoverage()) {
            var ticket = transactions.registerBackfillAndMarkWaiting(
                    job,
                    List.of("COVERAGE_INSUFFICIENT"),
                    clock.instant()
            );
            if (ticket.newlyCreated()) {
                marketCoveragePort.triggerBackfill(
                        ticket.backfillId(),
                        job.instrumentId(),
                        job.horizonSeconds(),
                        job.asOf()
                );
            }
            return true;
        }

        // 3. Model bundle, generation ve market snapshot bağlamını sabitle (pin)
        AnalysisJobRepository.PinnedContext pinned;
        var existingPin = transactions.findPinnedContext(job, clock.instant());
        if (existingPin.isPresent()) {
            pinned = existingPin.get();
        } else {
            ModelClient.Bundle active;
            try {
                active = modelClient.activeBundle(job.horizonSeconds());
            } catch (RuntimeException e) {
                transactions.fail(job, "MODEL_UNAVAILABLE", clock.instant());
                return true;
            }
            if (!active.supportedHorizons().contains(job.horizonSeconds())) {
                transactions.fail(job, "MODEL_NOT_READY", clock.instant());
                return true;
            }
            pinned = new AnalysisJobRepository.PinnedContext(
                    session.generation(),
                    market.datasetId(),
                    market.snapshotId(),
                    active.bundleId(),
                    active.featureVersion(),
                    active.featureSchemaHash(),
                    active.releaseStatus()
            );
            if (!transactions.pinContext(job, pinned, clock.instant())) {
                return true;
            }
        }

        // 4. Sabitlenmiş (pinned) bundle ile model çağrısı (DB transaction açık tutulmaz)
        var pinnedBundle = new ModelClient.Bundle(
                pinned.modelBundleId(),
                pinned.featureVersion(),
                pinned.featureSchemaHash(),
                List.of(job.horizonSeconds()),
                pinned.bundleReleaseStatus()
        );
        var flowResult = new AnalysisFlow(modelClient).evaluate(
                job.id(),
                job.instrumentId(),
                job.horizonSeconds(),
                job.asOf(),
                pinned.marketDatasetId(),
                pinned.marketSnapshotId(),
                pinnedBundle
        );

        if ("FAILED".equals(flowResult.status())) {
            transactions.fail(job, flowResult.reason(), clock.instant());
            return true;
        }

        PredictionResult prediction = flowResult.prediction();
        if ("SUCCEEDED".equals(flowResult.status()) && "WAIT".equals(flowResult.action())) {
            transactions.completeWait(job, prediction, clock.instant());
            return true;
        }

        // 5. READY numeric prediction -> Sürümlü NumericPolicy değerlendirmesi
        NumericPolicy.Artifact policy = transactions.findPolicyArtifact(policyVersion)
                .orElse(DEFAULT_POLICY_ARTIFACT);

        NumericPolicy.Outcome outcome = NumericPolicy.evaluate(
                prediction,
                policy,
                market.referencePrice(),
                market.verifiedAtr(),
                market.coverage(),
                session.estimatedRoundTripCost()
        );

        if ("WAIT".equals(outcome.action())) {
            transactions.completePolicyWait(job, prediction, outcome.reason(), clock.instant());
            return true;
        }

        // 6. İşlem sonunda oturum/generation güncellik kapısı (freshness gate)
        var latestSessionOpt = sessionContextReader.findOwnedSession(job.ownerId(), job.sessionId(), job.instrumentId());
        if (latestSessionOpt.isEmpty()
                || !"RUNNING".equals(latestSessionOpt.get().status())
                || latestSessionOpt.get().generation() != pinned.sessionGeneration()) {
            transactions.fail(job, "STALE_SESSION_GENERATION", clock.instant());
            return true;
        }

        // 7. Numeric sonuç + policy intent hazır olduğunda proposal TTL hemen başlar;
        // açıklama (explanation) çağrısının gecikmesi validUntil süresini ASLA uzatmaz.
        Instant readyAt = clock.instant();
        Instant validUntil = readyAt.plus(proposalTtl);

        List<String> finalReasons = new ArrayList<>(prediction.reasonCodes());
        if (!finalReasons.contains(outcome.reason())) {
            finalReasons.add(outcome.reason());
        }

        String explanationStatus;
        String explanationText;
        try {
            String generated = explanationClient.explain(new ExplanationClient.Request(
                    job.id(),
                    outcome.action(),
                    outcome.reason(),
                    finalReasons,
                    prediction.observedFeatureSummary()
            ));
            if (generated == null || generated.isBlank()) {
                throw new IllegalStateException("EMPTY_EXPLANATION");
            }
            explanationStatus = "READY";
            explanationText = generated;
        } catch (RuntimeException ex) {
            explanationStatus = "TEMPLATE";
            explanationText = ExplanationTemplate.render(
                    outcome.action(),
                    outcome.reason(),
                    finalReasons,
                    prediction.observedFeatureSummary()
            );
        }

        var intent = new AnalysisJobRepository.ProposalIntent(
                outcome.action(),
                "MARKET",
                null,
                outcome.stopLoss(),
                outcome.takeProfit(),
                outcome.suggestedLeverage(),
                job.horizonSeconds(),
                "CLOSE_AT_DEADLINE",
                market.referencePrice(),
                market.verifiedAtr()
        );

        var draft = new AnalysisJobRepository.ProposalDraft(
                UUID.randomUUID(),
                policy.version(),
                intent,
                validUntil,
                finalReasons,
                explanationStatus,
                explanationText
        );

        transactions.completeProposal(job, prediction, draft, clock.instant());
        return true;
    }
}

