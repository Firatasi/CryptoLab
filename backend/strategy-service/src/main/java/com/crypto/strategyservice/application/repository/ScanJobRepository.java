package com.crypto.strategyservice.application.repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface ScanJobRepository {

    record CandidateOutcome(
            int positionIndex,
            UUID instrumentId,
            boolean eligible,
            String skippedReason,
            UUID analysisId
    ) {
    }

    record ScanJobView(
            UUID id,
            UUID ownerId,
            UUID sessionId,
            int horizonSeconds,
            String universeVersion,
            String cursor,
            String status,
            long totalKnown,
            long evaluatedCount,
            long skippedCount,
            Map<String, Long> reasonCounts,
            List<CandidateOutcome> candidates,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    void createScan(
            UUID id,
            UUID ownerId,
            UUID sessionId,
            int horizonSeconds,
            String universeVersion,
            String initialCursor,
            long totalKnown,
            Instant now
    );

    boolean claimScan(
            UUID scanId,
            UUID workerToken,
            Instant now,
            Instant leaseUntil
    );

    boolean isCancelled(UUID scanId);

    boolean recordCandidateStep(
            UUID scanId,
            UUID workerToken,
            CandidateOutcome outcome,
            Instant asOf,
            Instant now
    );

    boolean finishScanStep(
            UUID scanId,
            UUID workerToken,
            String nextStatus,
            String nextCursor,
            Instant now
    );

    boolean cancel(
            UUID ownerId,
            UUID scanId,
            Instant now
    );

    Optional<ScanJobView> findScanView(
            UUID ownerId,
            UUID scanId
    );
}

