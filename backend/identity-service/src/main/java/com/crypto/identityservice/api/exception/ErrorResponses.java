package com.crypto.identityservice.api.exception;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

public final class ErrorResponses {

    private ErrorResponses() {
    }

    public static Map<String, Object> body(String code) {
        return Map.of(
                "code", code,
                "message", message(code),
                "traceId", UUID.randomUUID().toString(),
                "retryable", false,
                "details", Map.of()
        );
    }

    private static String message(String code) {
        return switch (code) {
            case "INVALID_CREDENTIALS" ->
                    "E-posta veya parola hatalı.";
            case "UNAUTHORIZED" ->
                    "Kimlik doğrulaması gerekli.";
            case "FORBIDDEN" ->
                    "Bu işlem için izin yok.";
            case "RATE_LIMITED" ->
                    "Çok fazla deneme. Daha sonra tekrar deneyin.";
            case "CONFLICT" ->
                    "Kayıt mevcut bilgilerle çakışıyor.";
            case "IDEMPOTENCY_CONFLICT" ->
                    "Aynı işlem anahtarı farklı içerikle kullanılamaz.";
            case "NOT_FOUND" ->
                    "Kayıt bulunamadı.";
            case "INVALID_REQUEST" ->
                    "İstek bilgileri geçersiz.";
            case "UNSUPPORTED_MEDIA_TYPE" ->
                    "İstek application/json olmalı.";
            default ->
                    "İstek tamamlanamadı.";
        };
    }

    // Security filtreleri yalnız bu iki sabit kodla çağırır.
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