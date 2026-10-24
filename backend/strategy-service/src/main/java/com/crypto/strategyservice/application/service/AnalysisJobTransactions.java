package com.crypto.strategyservice.application.service;

import com.crypto.strategyservice.application.repository.AnalysisJobRepository;
import com.crypto.strategyservice.domain.model.PredictionResult;
import com.crypto.strategyservice.domain.service.NumericPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class AnalysisJobTransactions {

    private final AnalysisJobRepository jobs;

    public AnalysisJobTransactions(AnalysisJobRepository jobs) {
        this.jobs = jobs;
    }

    @Transactional
    public UUID enqueueIdempotent(
            UUID ownerId,
            UUID sessionId,
            UUID instrumentId,
            int horizonSeconds,
            Instant asOf,
            String idempotencyKey,
            Instant now
    ) {
        return jobs.enqueueIdempotent(
                ownerId,
                sessionId,
                instrumentId,
                horizonSeconds,
                asOf,
                idempotencyKey,
                now
        );
    }

    @Transactional
    public Optional<AnalysisJobRepository.ClaimedJob> claimNext(
            Instant now,
            Duration leaseDuration
    ) {
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("INVALID_JOB_LEASE");
        }

        return jobs.claimNext(
                UUID.randomUUID(),
                now,
                now.plus(leaseDuration)
        );
    }

    @Transactional(readOnly = true)
    public Optional<AnalysisJobRepository.PinnedContext> findPinnedContext(
            AnalysisJobRepository.ClaimedJob job,
            Instant now
    ) {
        return jobs.findPinnedContext(
                job.id(),
                job.workerToken(),
                now
        );
    }

    @Transactional
    public boolean pinContext(
            AnalysisJobRepository.ClaimedJob job,
            AnalysisJobRepository.PinnedContext context,
            Instant now
    ) {
        return jobs.pinContext(
                job.id(),
                job.workerToken(),
                context,
                now
        );
    }

    @Transactional
    public boolean fail(
            AnalysisJobRepository.ClaimedJob job,
            String error,
            Instant now
    ) {
        return jobs.fail(
                job.id(),
                job.workerToken(),
                error,
                now
        );
    }

    @Transactional
    public boolean completeWait(
            AnalysisJobRepository.ClaimedJob job,
            PredictionResult prediction,
            Instant now
    ) {
        return jobs.completeWait(
                job.id(),
                job.workerToken(),
                prediction,
                now
        );
    }

    @Transactional
    public boolean completePolicyWait(
            AnalysisJobRepository.ClaimedJob job,
            PredictionResult prediction,
            String policyReason,
            Instant now
    ) {
        return jobs.completePolicyWait(
                job.id(),
                job.workerToken(),
                prediction,
                policyReason,
                now
        );
    }

    @Transactional
    public AnalysisJobRepository.BackfillTicket registerBackfillAndMarkWaiting(
            AnalysisJobRepository.ClaimedJob job,
            List<String> reasonCodes,
            Instant now
    ) {
        var ticket = jobs.registerSingleFlightBackfill(
                job.instrumentId(),
                job.horizonSeconds(),
                job.asOf(),
                now
        );
        jobs.markWaitingData(
                job.id(),
                job.workerToken(),
                ticket.backfillId(),
                reasonCodes,
                now
        );
        return ticket;
    }

    @Transactional
    public boolean completeProposal(
            AnalysisJobRepository.ClaimedJob job,
            PredictionResult prediction,
            AnalysisJobRepository.ProposalDraft draft,
            Instant now
    ) {
        return jobs.completeProposal(
                job.id(),
                job.workerToken(),
                prediction,
                draft,
                now
        );
    }

    @Transactional
    public void savePolicyArtifact(
            UUID id,
            NumericPolicy.Artifact artifact
    ) {
        jobs.savePolicyArtifact(id, artifact);
    }

    @Transactional(readOnly = true)
    public Optional<NumericPolicy.Artifact> findPolicyArtifact(String version) {
        return jobs.findPolicyArtifact(version);
    }

    @Transactional(readOnly = true)
    public Optional<AnalysisJobRepository.AnalysisView> findJobView(UUID ownerId, UUID jobId) {
        return jobs.findJobView(ownerId, jobId);
    }

    @Transactional(readOnly = true)
    public Optional<AnalysisJobRepository.ProposalView> findProposalView(UUID ownerId, UUID proposalId, int revision) {
        return jobs.findProposalView(ownerId, proposalId, revision);
    }
}
