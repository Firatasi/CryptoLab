package com.crypto.simulationservice.application.command;

import java.util.UUID;

public record SubmitOrderCommand(UUID planId, int revision, String planHash) {

    public SubmitOrderCommand {
        // HTTP validation tek giriş noktası değildir; application sınırı da korunur.
        if (planId == null || revision < 1 || planHash == null
                || !planHash.matches("^sha256:[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("INVALID_SUBMIT");
        }
    }
}
