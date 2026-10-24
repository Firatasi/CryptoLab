package com.crypto.simulationservice.api.dto.request;

import java.util.UUID;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record SubmitOrderRequest(@NotNull
        UUID planId, @Min(1)
        int revision,
        @NotNull
        @Pattern(regexp = "^sha256:[0-9a-f]{64}$")
        String planHash) {
    // quantity, direction, fee tekrar alınmaz; onaylanan plan sunucudan okunur.

}
