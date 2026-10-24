package com.crypto.simulationservice.application.repository;

import com.crypto.simulationservice.domain.model.Journal;

import java.time.Instant;
import java.util.UUID;

public interface LedgerRepository {

    // Journal, posting ve bakiye değişikliklerini
    // çağıran servisin transaction'ı içinde kaydeder.
    UUID post(
            Journal journal,
            Instant effectiveAt,
            Instant recordedAt
    );
}