package com.crypto.reportingservice.api.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;
import java.util.UUID;

@RestControllerAdvice
public class ReportingExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ReportingExceptionHandler.class);

    @ExceptionHandler({
            IllegalArgumentException.class,
            MethodArgumentNotValidException.class,
            MethodArgumentTypeMismatchException.class,
            MissingRequestHeaderException.class,
            MissingServletRequestParameterException.class
    })
    public ResponseEntity<Map<String, Object>> badRequest(Exception ex) {
        return error(400, "INVALID_REQUEST", "İstek parametreleri geçersiz.", false);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> forbidden(AccessDeniedException ex) {
        return error(403, "FORBIDDEN", "Bu işlem için izin yok.", false);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(NoResourceFoundException ex) {
        return error(404, "NOT_FOUND", "Kayıt bulunamadı.", false);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> methodNotAllowed(HttpRequestMethodNotSupportedException ex) {
        return error(405, "METHOD_NOT_ALLOWED", "Bu HTTP yöntemi desteklenmiyor.", false);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> statusException(ResponseStatusException ex) {
        int status = ex.getStatusCode().value();
        String code = status == 400 ? "INVALID_REQUEST"
                : status == 403 ? "FORBIDDEN"
                : status == 404 ? "NOT_FOUND"
                : "INTERNAL_ERROR";
        String msg = status >= 500 ? "Rapor servisi işlemi tamamlayamadı." : "İstek tamamlanamadı.";
        return error(status, code, msg, status >= 500);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception ex) {
        String traceId = UUID.randomUUID().toString();
        log.error("Reporting unexpected failure traceId={} type={}", traceId, ex.getClass().getName());
        return ResponseEntity.internalServerError()
                .header("Cache-Control", "no-store")
                .body(Map.of(
                        "code", "INTERNAL_ERROR",
                        "message", "Rapor servisi işlemi tamamlayamadı.",
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
