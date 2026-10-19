package com.crypto.simulationservice.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Sorumluluk: İş verisini ve kendi değişmez kurallarını temsil eder. Okuma
 * ipucu: Modelin HTTP veya veritabanı biçimine bağımlı olup olmadığını kontrol
 * et.
 */
public record ExecutionPlan(UUID planId, int revision, String planHash, UUID sessionId,
        UUID instrumentId, long instrumentVersion, String source, UUID proposalId, long positionGeneration,
        String side, String orderType, BigDecimal quantity, BigDecimal referenceEntry, BigDecimal limitPrice,
        BigDecimal marginRequired, BigDecimal openFeeEstimate, BigDecimal notional, BigDecimal leverage,
        BigDecimal stopLoss, BigDecimal takeProfit, int horizonSeconds, String expiryPolicy,
        BigDecimal priceLowerBound, BigDecimal priceUpperBound, BigDecimal maxOpenFee,
        BigDecimal maxSlippageBps, UUID ruleProfileId, long riskPolicyVersion, Instant createdAt,
        Instant validUntil, BigDecimal estimatedLiquidationPrice, List<String> warnings) {

    public ExecutionPlan {
        warnings = List.copyOf(warnings);
    }
    // Bu domain record BigDecimal taşır. HTTP mapper Decimal.wire ile string üretir.
    // Controller Jackson'ın varsayılan numeric BigDecimal çıktısını doğrudan kullanmamalı.
}
