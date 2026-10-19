package com.crypto.simulationservice.application.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface OrderSubmissionRepository {

    record Previous(String requestHash, UUID commandId) {}

    record Plan(String hash, String state, Instant validUntil, UUID instrumentId, long generation) {}

    Optional<UUID> findSession(UUID plan, int revision);

    boolean lockOwnedSession(UUID session, UUID owner);

    Optional<Previous> previous(UUID owner, String route, UUID session, String key);

    Plan lockPlan(UUID plan, int revision);

    long generation(UUID session, UUID instrument);

    String sessionStatus(UUID session);

    void accept(UUID command, UUID session, UUID owner, UUID plan, int revision,
            String hash, String route, String key, String requestHash, Instant now);

    Instant acceptedAt(UUID command);
}
