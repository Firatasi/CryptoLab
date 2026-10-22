package com.crypto.strategyservice.application.port;

import java.util.Optional;
import java.util.UUID;

/**
 * Sorumluluk: Oturum sahipliği ve generation bilgisini yetkili internal sınırdan sorgular.
 * Strategy doğrudan Simulation veritabanına SQL atmaz.
 */
public interface SessionContextReader {

    record SessionSnapshot(
            UUID sessionId,
            UUID ownerId,
            UUID instrumentId,
            long generation,
            String status,
            double estimatedRoundTripCost
    ) {
        public SessionSnapshot {
            if (sessionId == null || ownerId == null || instrumentId == null || status == null || status.isBlank()) {
                throw new IllegalArgumentException("INVALID_SESSION_SNAPSHOT");
            }
            if (generation < 0 || !Double.isFinite(estimatedRoundTripCost) || estimatedRoundTripCost < 0) {
                throw new IllegalArgumentException("INVALID_SESSION_SNAPSHOT");
            }
        }
    }

    Optional<SessionSnapshot> findOwnedSession(UUID ownerId, UUID sessionId, UUID instrumentId);
}

