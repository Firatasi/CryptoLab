package com.crypto.strategyservice.api.exception;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

public final class ErrorResponses {

    private ErrorResponses() {
    }

    public static Map<String, Object> body(String code, boolean retryable) {
        return Map.of(
                "code", code,
                "message", message(code),
                "traceId", UUID.randomUUID().toString(),
                "retryable", retryable,
                "details", Map.of()
        );
    }

    private static String message(String code) {
        return switch (code) {
            case "UNAUTHORIZED" -> "Kimlik doğrulaması gerekli.";
            case "FORBIDDEN" -> "Bu işlem için izin yok.";
            case "PAYLOAD_TOO_LARGE" -> "Yüklenen içerik boyut sınırını aşıyor.";
            case "CONFLICT", "IDEMPOTENCY_CONFLICT", "ALIAS_VERSION_CONFLICT" -> "İstek mevcut durumla çakışıyor.";
            case "TRAINING_UNAVAILABLE" -> "Eğitim servisi şu anda kullanılamıyor.";
            case "NOT_FOUND" -> "Kayıt bulunamadı.";
            default -> "İstek bilgileri geçersiz.";
        };
    }

    public static void write(
            HttpServletResponse response,
            int status,
            String code
    ) throws IOException {
        if (!"UNAUTHORIZED".equals(code) && !"FORBIDDEN".equals(code)) {
            throw new IllegalArgumentException("INVALID_SECURITY_ERROR_CODE");
        }

        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");

        response.getWriter().write(
                "{\"code\":\"" + code
                        + "\",\"message\":\"" + message(code)
                        + "\",\"traceId\":\"" + UUID.randomUUID()
                        + "\",\"retryable\":false,\"details\":{}}"
        );
    }
}

