package com.crypto.simulationservice.api.dto.request;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record ManualPreviewRequest(
        @NotNull
        @Pattern(regexp = "MANUAL")
        String source,
        @NotNull
        UUID sessionId,
        @NotNull
        UUID instrumentId,
        @NotNull
        @Pattern(regexp = "LONG|SHORT")
        String side,
        @NotNull
        @Pattern(regexp = "MARKET|LIMIT")
        String orderType,
        @NotBlank
        String marginBudget,
        @NotBlank
        String leverage,
        String limitPrice,
        String stopLoss,
        String takeProfit,
        int horizonSeconds,
        @NotNull
        @Pattern(regexp = "REEVALUATE|CLOSE_AT_DEADLINE")
        String expiryPolicy,
        UUID originProposalId) {
    // JSON alanı String: büyük finans sayıları JavaScript number hassasiyetine teslim edilmez.

    public ManualPreviewRequest(
            String source,
            UUID sessionId,
            UUID instrumentId,
            String side,
            String orderType,
            String marginBudget,
            String leverage,
            String limitPrice,
            String stopLoss,
            String takeProfit,
            int horizonSeconds,
            String expiryPolicy
    ) {
        this(
                source,
                sessionId,
                instrumentId,
                side,
                orderType,
                marginBudget,
                leverage,
                limitPrice,
                stopLoss,
                takeProfit,
                horizonSeconds,
                expiryPolicy,
                null
        );
    }
}
