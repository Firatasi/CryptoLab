package com.crypto.strategyservice.application.service;

import com.crypto.strategyservice.application.port.ExplanationClient;
import com.crypto.strategyservice.application.port.MarketCoveragePort;
import com.crypto.strategyservice.application.port.ModelClient;
import com.crypto.strategyservice.application.port.SessionContextReader;
import com.crypto.strategyservice.application.repository.AnalysisJobRepository;
import com.crypto.strategyservice.domain.model.PredictionResult;
import com.crypto.strategyservice.domain.service.AdaptiveNumericPolicy;
import com.crypto.strategyservice.domain.service.ExplanationTemplate;
import com.crypto.strategyservice.domain.service.NumericPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

    private static final NumericPolicy.Artifact DEFAULT_ADAPTIVE_POLICY_ARTIFACT = new NumericPolicy.Artifact(
            "quant-adaptive-v2",
            0.0022,
            0.90,
            new BigDecimal("1.65"),
            new BigDecimal("2.0"),
            new BigDecimal("10"),
            0.65
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

    @Autowired
    public AnalysisWorker(
            AnalysisJobTransactions transactions,
            ModelClient modelClient,
            SessionContextReader sessionContextReader,
            MarketCoveragePort marketCoveragePort,
            ExplanationClient explanationClient,
            Clock clock,
            @Value("${strategy.worker.lease-seconds:30}") long leaseSeconds,
            @Value("${strategy.proposal.ttl-seconds:60}") long proposalTtlSeconds,
            @Value("${strategy.policy.version:quant-adaptive-v2}") String policyVersion
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
        return enqueue(ownerId, sessionId, instrumentId, horizonSeconds, asOf, idempotencyKey, null);
    }

    public UUID enqueue(
            UUID ownerId,
            UUID sessionId,
            UUID instrumentId,
            int horizonSeconds,
            Instant asOf,
            String idempotencyKey,
            String riskLevel
    ) {
        if (!SUPPORTED_HORIZONS.contains(horizonSeconds)) {
            throw new IllegalArgumentException("INVALID_HORIZON");
        }
        var session = sessionContextReader.findOwnedSession(ownerId, sessionId, instrumentId)
                .orElseThrow(() -> new IllegalStateException("SESSION_NOT_FOUND"));
        if (!"RUNNING".equals(session.status())) {
            throw new IllegalStateException("SESSION_HALTED");
        }
        String normalizedRisk = AdaptiveNumericPolicy.normalizeRiskLevel(riskLevel);
        return transactions.enqueueIdempotent(
                ownerId,
                sessionId,
                instrumentId,
                horizonSeconds,
                asOf,
                idempotencyKey,
                clock.instant(),
                normalizedRisk == null ? "AUTO" : normalizedRisk
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

        // 5. READY numeric prediction -> Sürümlü NumericPolicy / AdaptiveNumericPolicy değerlendirmesi
        boolean useLegacyPolicy = "policy-v1".equals(policyVersion)
                && !(prediction.featureVersion() != null && prediction.featureVersion().startsWith("features-quant-v"));
        NumericPolicy.Artifact fallbackArtifact = useLegacyPolicy
                ? DEFAULT_POLICY_ARTIFACT
                : DEFAULT_ADAPTIVE_POLICY_ARTIFACT;
        NumericPolicy.Artifact policy = transactions.findPolicyArtifact(policyVersion)
                .orElse(fallbackArtifact);

        NumericPolicy.Outcome outcome;
        String effectiveRisk = null;
        SessionContextReader.SessionRiskProfile riskProfile = null;
        int instMaxLev = 125;
        if (useLegacyPolicy) {
            outcome = NumericPolicy.evaluate(
                    prediction,
                    policy,
                    market.referencePrice(),
                    market.verifiedAtr(),
                    market.coverage(),
                    session.estimatedRoundTripCost()
            );
        } else {
            riskProfile = sessionContextReader.findSessionRiskProfile(job.ownerId(), job.sessionId());
            instMaxLev = marketCoveragePort.maxLeverageForInstrument(job.instrumentId());
            effectiveRisk = (job.riskLevel() == null || job.riskLevel().isBlank() || "AUTO".equalsIgnoreCase(job.riskLevel()))
                    ? AdaptiveNumericPolicy.riskLevelFromPolicyId(riskProfile.riskPolicyId())
                    : job.riskLevel();
            outcome = AdaptiveNumericPolicy.evaluateAdaptive(
                    prediction,
                    policy,
                    market.referencePrice(),
                    market.verifiedAtr(),
                    market.coverage(),
                    session.estimatedRoundTripCost(),
                    effectiveRisk,
                    riskProfile.maxLeverage(),
                    instMaxLev
            );
        }

        if ("WAIT".equals(outcome.action())) {
            String waitSummary = useLegacyPolicy ? null : AdaptiveNumericPolicy.buildWaitSummary(
                    prediction,
                    outcome.reason(),
                    job.horizonSeconds(),
                    market.referencePrice(),
                    market.verifiedAtr(),
                    effectiveRisk,
                    riskProfile.maxLeverage(),
                    instMaxLev,
                    session.estimatedRoundTripCost()
            );
            transactions.completePolicyWait(job, prediction, outcome.reason(), waitSummary, clock.instant());
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
                    prediction.observedFeatureSummary(),
                    pinned.modelBundleId(),
                    prediction,
                    outcome
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

        PullbackEntry pullbackEntry = resolvePullbackEntry(
                useLegacyPolicy,
                job.horizonSeconds(),
                outcome,
                finalReasons,
                market.referencePrice(),
                market.verifiedAtr()
        );
        var intent = new AnalysisJobRepository.ProposalIntent(
                outcome.action(),
                pullbackEntry.orderType(),
                pullbackEntry.limitPrice(),
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

    private record PullbackEntry(String orderType, BigDecimal limitPrice) {
    }

    private static PullbackEntry resolvePullbackEntry(
            boolean useLegacyPolicy,
            int horizonSeconds,
            NumericPolicy.Outcome outcome,
            List<String> reasons,
            BigDecimal referencePrice,
            BigDecimal verifiedAtr
    ) {
        if (useLegacyPolicy
                || !"QUANT_ADAPTIVE_POLICY".equals(outcome.reason())
                || horizonSeconds < 900
                || referencePrice == null || referencePrice.signum() <= 0
                || verifiedAtr == null || verifiedAtr.signum() <= 0
                || outcome.stopLoss() == null || outcome.takeProfit() == null) {
            return new PullbackEntry("MARKET", null);
        }
        if (reasons.contains("SQUEEZE_BREAKOUT_SETUP")
                || reasons.contains("TREND_MOMENTUM_ALIGNED")
                || reasons.contains("IMPULSE_MOMENTUM_ALIGNED")) {
            return new PullbackEntry("MARKET", null);
        }

        double ref = referencePrice.doubleValue();
        double atrFrac = verifiedAtr.doubleValue() / ref;
        double pullbackFrac = Math.max(0.0005, Math.min(0.0018, atrFrac * 0.14));
        boolean isLong = "LONG".equals(outcome.action());
        BigDecimal rawLimit = isLong
                ? referencePrice.multiply(BigDecimal.valueOf(1.0 - pullbackFrac))
                : referencePrice.multiply(BigDecimal.valueOf(1.0 + pullbackFrac));

        BigDecimal tickSize = inferSimulationTickSize(referencePrice);
        RoundingMode tickRound = isLong ? RoundingMode.FLOOR : RoundingMode.CEILING;
        BigDecimal alignedLimit = rawLimit.divide(tickSize, 0, tickRound).multiply(tickSize).stripTrailingZeros();
        if (alignedLimit.scale() < 0) {
            alignedLimit = alignedLimit.setScale(0, RoundingMode.UNNECESSARY);
        }

        boolean strictlyBetterAndValid = isLong
                ? (alignedLimit.compareTo(referencePrice) < 0
                        && alignedLimit.compareTo(outcome.stopLoss()) > 0
                        && alignedLimit.compareTo(outcome.takeProfit()) < 0)
                : (alignedLimit.compareTo(referencePrice) > 0
                        && alignedLimit.compareTo(outcome.stopLoss()) < 0
                        && alignedLimit.compareTo(outcome.takeProfit()) > 0);
        if (!strictlyBetterAndValid) {
            return new PullbackEntry("MARKET", null);
        }
        return new PullbackEntry("LIMIT", alignedLimit);
    }

    private static BigDecimal inferSimulationTickSize(BigDecimal price) {
        if (price.compareTo(new BigDecimal("100")) >= 0) {
            return new BigDecimal("0.01");
        }
        if (price.compareTo(BigDecimal.ONE) >= 0) {
            return new BigDecimal("0.001");
        }
        return new BigDecimal("0.00001");
    }
}

