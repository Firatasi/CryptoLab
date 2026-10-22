package com.crypto.strategyservice.application.port;

import java.util.List;
import java.util.UUID;

/**
 * Sorumluluk: Piyasa taraması için kararlı universe snapshot adaylarını sağlar.
 */
public interface MarketUniversePort {

    record Candidate(
            UUID instrumentId,
            boolean eligible,
            String skippedReason
    ) {
        public Candidate {
            if (instrumentId == null) {
                throw new IllegalArgumentException("INVALID_SCAN_CANDIDATE");
            }
            if (!eligible && (skippedReason == null || skippedReason.isBlank())) {
                throw new IllegalArgumentException("SKIPPED_REASON_REQUIRED");
            }
            if (eligible && skippedReason != null) {
                throw new IllegalArgumentException("ELIGIBLE_CANDIDATE_CANNOT_HAVE_SKIPPED_REASON");
            }
        }
    }

    record UniverseSnapshot(
            String universeVersion,
            List<Candidate> candidates
    ) {
        public UniverseSnapshot {
            if (universeVersion == null || universeVersion.isBlank()) {
                throw new IllegalArgumentException("INVALID_UNIVERSE_VERSION");
            }
            candidates = List.copyOf(candidates);
        }
    }

    UniverseSnapshot currentUniverse(int horizonSeconds);
}

