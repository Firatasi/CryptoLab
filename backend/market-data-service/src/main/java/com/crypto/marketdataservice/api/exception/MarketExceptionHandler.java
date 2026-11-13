package com.crypto.marketdataservice.api.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;
import java.util.UUID;

@RestControllerAdvice
public class MarketExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(MarketExceptionHandler.class);

    @ExceptionHandler({
            IllegalArgumentException.class,
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class
    })
    public ResponseEntity<Map<String, Object>> badRequest(Exception ex) {
        return error(400, "INVALID_REQUEST", "Piyasa sorgu parametreleri geçersiz.", false);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(NoResourceFoundException ex) {
        return error(404, "NOT_FOUND", "Kayıt bulunamadı.", false);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> methodNotAllowed(HttpRequestMethodNotSupportedException ex) {
        return error(405, "METHOD_NOT_ALLOWED", "Bu HTTP yöntemi desteklenmiyor.", false);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> unavailable(IllegalStateException ex) {
        if ("TOO_MANY_STREAMS".equals(ex.getMessage())) {
            return error(503, "SERVICE_UNAVAILABLE", "Canlı yayın bağlantı kapasitesi geçici olarak dolu.", true);
        }
        return error(503, "SERVICE_UNAVAILABLE", "Piyasa verisi geçici olarak kullanılamıyor.", true);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception ex) {
        String traceId = UUID.randomUUID().toString();
        log.error("Market unexpected failure traceId={} type={}", traceId, ex.getClass().getName());
        return ResponseEntity.internalServerError()
                .header("Cache-Control", "no-store")
                .body(Map.of(
                        "code", "INTERNAL_ERROR",
                        "message", "Piyasa verisi işlenemedi.",
                        "traceId", traceId,
                        "retryable", true,
                        "details", Map.of()
                ));
    }

    private ResponseEntity<Map<String, Object>> error(int status, String code, String message, boolean retryable) {
        return ResponseEntity.status(status)
                .header("Cache-Control", "no-store")
                .body(Map.of(
                        "code", code,
                        "message", message,
                        "traceId", UUID.randomUUID().toString(),
                        "retryable", retryable,
                        "details", Map.of()
                ));
    }
}
