package com.crypto.strategyservice.application.repository;

import com.crypto.strategyservice.domain.model.PredictionResult;
import com.crypto.strategyservice.domain.service.NumericPolicy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AnalysisJobRepository {

    record ClaimedJob(
            UUID id,
            UUID ownerId,
            UUID sessionId,
            UUID instrumentId,
            int horizonSeconds,
            Instant asOf,
            UUID workerToken
    ) {
    }

    record PinnedContext(
            long sessionGeneration,
            UUID marketDatasetId,
            UUID marketSnapshotId,
            UUID modelBundleId,
            String featureVersion,
            String featureSchemaHash,
            String bundleReleaseStatus
    ) {
        public PinnedContext {
            if (sessionGeneration < 0
                    || marketDatasetId == null
                    || marketSnapshotId == null
                    || modelBundleId == null
                    || featureVersion == null || featureVersion.isBlank()
                    || featureSchemaHash == null || featureSchemaHash.isBlank()
                    || bundleReleaseStatus == null || bundleReleaseStatus.isBlank()) {
                throw new IllegalArgumentException("INVALID_ANALYSIS_CONTEXT");
            }
        }
    }

    record BackfillTicket(
            UUID backfillId,
            boolean newlyCreated
    ) {
    }

    record ProposalIntent(
            String side,
            String orderType,
            BigDecimal limitPrice,
            BigDecimal stopLoss,
            BigDecimal takeProfit,
            BigDecimal suggestedLeverage,
            int horizonSeconds,
            String expiryPolicy,
            BigDecimal referencePrice,
            BigDecimal verifiedAtr
    ) {
        public ProposalIntent {
            if (!("LONG".equals(side) || "SHORT".equals(side))) {
                throw new IllegalArgumentException("INVALID_PROPOSAL_SIDE");
            }
            if (stopLoss == null || stopLoss.signum() <= 0
                    || takeProfit == null || takeProfit.signum() <= 0
                    || suggestedLeverage == null || suggestedLeverage.signum() <= 0) {
                throw new IllegalArgumentException("INVALID_PROPOSAL_BRACKET");
            }
        }
    }

    record ProposalDraft(
            UUID proposalId,
            String policyVersion,
            ProposalIntent intent,
            Instant validUntil,
            List<String> reasonCodes,
            String explanationStatus,
            String explanation
    ) {
        public ProposalDraft {
            if (proposalId == null
                    || policyVersion == null || policyVersion.isBlank()
                    || intent == null
                    || validUntil == null
                    || explanationStatus == null || explanationStatus.isBlank()) {
                throw new IllegalArgumentException("INVALID_PROPOSAL_DRAFT");
            }
            reasonCodes = List.copyOf(reasonCodes);
        }
    }

    record AnalysisView(
            UUID id,
            UUID ownerId,
            UUID sessionId,
            UUID instrumentId,
            int horizonSeconds,
            Instant asOf,
            String status,
            String action,
            String error,
            List<String> reasonCodes,
            UUID modelBundleId,
            String bundleReleaseStatus,
            UUID featureSnapshotId,
            UUID proposalId,
            String explanationStatus,
            String explanation,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    record ProposalView(
            UUID proposalId,
            int revision,
            UUID ownerId,
            UUID sessionId,
            UUID instrumentId,
            long generation,
            String policyVersion,
            UUID modelBundleId,
            UUID featureSnapshotId,
            ProposalIntent intent,
            Instant validUntil,
            String state
    ) {
    }

    void createQueued(
            UUID id,
            UUID ownerId,
            UUID sessionId,
            UUID instrumentId,
            int horizonSeconds,
            Instant asOf,
            Instant now
    );

    UUID enqueueIdempotent(
            UUID ownerId,
            UUID sessionId,
            UUID instrumentId,
            int horizonSeconds,
            Instant asOf,
            String idempotencyKey,
            Instant now
    );

    Optional<ClaimedJob> claimNext(
            UUID workerToken,
            Instant now,
            Instant leaseUntil
    );

    boolean fail(
            UUID jobId,
            UUID workerToken,
            String error,
            Instant now
    );

    boolean pinContext(
            UUID jobId,
            UUID workerToken,
            PinnedContext context,
            Instant now
    );

    Optional<PinnedContext> findPinnedContext(
            UUID jobId,
            UUID workerToken,
            Instant now
    );

    boolean completeWait(
            UUID jobId,
            UUID workerToken,
            PredictionResult prediction,
            Instant now
    );

    boolean completePolicyWait(
            UUID jobId,
            UUID workerToken,
            PredictionResult prediction,
            String policyReason,
            Instant now
    );

    BackfillTicket registerSingleFlightBackfill(
            UUID instrumentId,
            int horizonSeconds,
            Instant asOf,
            Instant now
    );

    boolean markWaitingData(
            UUID jobId,
            UUID workerToken,
            UUID backfillId,
            List<String> reasonCodes,
            Instant now
    );

    boolean completeProposal(
            UUID jobId,
            UUID workerToken,
            PredictionResult prediction,
            ProposalDraft draft,
            Instant now
    );

    void savePolicyArtifact(
            UUID id,
            NumericPolicy.Artifact artifact
    );

    Optional<NumericPolicy.Artifact> findPolicyArtifact(
            String version
    );

    Optional<AnalysisView> findJobView(
            UUID ownerId,
            UUID jobId
    );

    Optional<ProposalView> findProposalView(
            UUID ownerId,
            UUID proposalId,
            int revision
    );
}
