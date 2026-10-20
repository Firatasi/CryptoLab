package com.crypto.apigateway.api.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.UUID;

@RestControllerAdvice
public class GatewayErrors {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRoute(IllegalArgumentException e) {
        return ResponseEntity.status(404).body(Map.of(
                "code", "NO_ROUTE",
                "message", "İstenen yol bulunamadı.",
                "traceId", UUID.randomUUID().toString(),
                "retryable", false,
                "details", Map.of()
        ));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> serviceUnavailable(IllegalStateException e) {
        return ResponseEntity.status(503).body(Map.of(
                "code", "SERVICE_UNAVAILABLE",
                "message", "Hedef servis şu anda kullanılamıyor.",
                "traceId", UUID.randomUUID().toString(),
                "retryable", true,
                "details", Map.of()
        ));
    }
}

