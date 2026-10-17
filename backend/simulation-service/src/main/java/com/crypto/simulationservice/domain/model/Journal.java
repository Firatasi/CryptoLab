package com.crypto.simulationservice.domain.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record Journal(
        UUID id,
        UUID sessionId,
        String businessKey,
        String requestHash,
        String reason,
        List<Posting> postings
) {

    public record Posting(
            UUID accountId,
            BigDecimal amount
    ) {
    }

    public Journal {
        // Dışarıdaki listenin sonradan değiştirilmesinden etkilenmeyelim.
        postings = List.copyOf(postings);

        BigDecimal total = postings.stream()
                .map(Posting::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Her finans hareketinin karşı kaydı olmalı ve toplamı sıfır olmalı.
        if (postings.size() < 2 || total.signum() != 0) {
            throw new IllegalArgumentException("UNBALANCED_JOURNAL");
        }
    }
}