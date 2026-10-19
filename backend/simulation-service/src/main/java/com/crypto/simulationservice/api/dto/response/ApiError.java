package com.crypto.simulationservice.api.dto.response;

import java.util.Map;

public record ApiError(String code, String message, String traceId,
        boolean retryable, Map<String, Object> details) {

    public ApiError {
        details = Map.copyOf(details);
    }
}
