package com.crypto.simulationservice.application.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Sorumluluk: Strategy servisindeki öneriyi (proposal) internal sınır üzerinden sorgular.
 * Öneri miktar, marjin veya komisyon otoritesi taşımaz; yalnız yön ve fiyat niyeti taşır.
 */
public interface ProposalReader {

    record ProposalSnapshot(
            UUID proposalId,
            int revision,
            UUID ownerId,
            UUID sessionId,
            UUID instrumentId,
            long generation,
            String side,
            String orderType,
            BigDecimal limitPrice,
            BigDecimal stopLoss,
            BigDecimal takeProfit,
            BigDecimal suggestedLeverage,
            int horizonSeconds,
            String expiryPolicy,
            Instant validUntil,
            String state
    ) {
    }

    Optional<ProposalSnapshot> findProposal(
            UUID ownerId,
            UUID sessionId,
            UUID proposalId,
            int revision
    );
}

