package com.crypto.apigateway.api.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.util.Map;
import java.util.UUID;

@RestControllerAdvice
public class GatewayErrors {

    @ExceptionHandler({IllegalArgumentException.class, NoResourceFoundException.class})
    public ResponseEntity<Map<String, Object>> badRoute(Exception e) {
        return error(404, "NO_ROUTE", "İstenen yol bulunamadı.", false);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> methodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return error(405, "METHOD_NOT_ALLOWED", "Bu HTTP yöntemi desteklenmiyor.", false);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> serviceUnavailable(IllegalStateException e) {
        return error(503, "SERVICE_UNAVAILABLE", "Hedef servis şu anda kullanılamıyor.", true);
    }

    @ExceptionHandler(HttpTimeoutException.class)
    public ResponseEntity<Map<String, Object>> gatewayTimeout(HttpTimeoutException e) {
        return error(504, "GATEWAY_TIMEOUT", "Servis yanıt süresi aşıldı. Lütfen tekrar deneyin.", true);
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String, Object>> badGateway(IOException e) {
        return error(502, "BAD_GATEWAY", "Hedef servise şu anda ulaşılamıyor.", true);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception e) {
        return error(500, "INTERNAL_ERROR", "Beklenmeyen bir ağ geçidi hatası oluştu.", true);
    }

    private ResponseEntity<Map<String, Object>> error(int status, String code, String message, boolean retryable) {
        return ResponseEntity.status(status).body(Map.of(
                "code", code,
                "message", message,
                "traceId", UUID.randomUUID().toString(),
                "retryable", retryable,
                "details", Map.of()
        ));
    }
}

