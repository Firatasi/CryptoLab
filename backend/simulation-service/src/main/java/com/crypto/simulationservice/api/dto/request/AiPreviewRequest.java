package com.crypto.simulationservice.api.dto.request;

import java.util.UUID;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Sorumluluk: AI_PROPOSAL önizleme isteğini tarif eder.
 * Yalnız sessionId, proposalId ve proposalRevision taşır; miktar, marjin veya komisyon otoritesi taşımaz.
 */
public record AiPreviewRequest(
        @NotNull
        @Pattern(regexp = "AI_PROPOSAL")
        String source,
        @NotNull
        UUID sessionId,
        @NotNull
        UUID proposalId,
        @Min(1)
        int proposalRevision
) {
}

