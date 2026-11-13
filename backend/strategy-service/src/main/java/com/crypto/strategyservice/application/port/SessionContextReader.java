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

    record SessionRiskProfile(
            UUID riskPolicyId,
            int maxLeverage
    ) {
        public static final UUID BALANCED_POLICY_ID = UUID.fromString("99999999-9999-4999-8999-999999999999");
        public static final UUID CONSERVATIVE_POLICY_ID = UUID.fromString("99999999-9999-4999-8999-999999999998");
        public static final UUID ADVANCED_POLICY_ID = UUID.fromString("99999999-9999-4999-8999-999999999997");
    }

    Optional<SessionSnapshot> findOwnedSession(UUID ownerId, UUID sessionId, UUID instrumentId);

    default SessionRiskProfile findSessionRiskProfile(UUID ownerId, UUID sessionId) {
        return new SessionRiskProfile(SessionRiskProfile.BALANCED_POLICY_ID, 20);
    }
}

