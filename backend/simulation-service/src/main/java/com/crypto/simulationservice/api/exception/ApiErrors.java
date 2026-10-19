package com.crypto.simulationservice.api.exception;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.crypto.simulationservice.api.dto.response.ApiError;
import com.crypto.simulationservice.domain.exception.BusinessException;

@RestControllerAdvice
public class ApiErrors {

    private static final Logger LOG = LoggerFactory.getLogger(ApiErrors.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiError> business(BusinessException exception) {
        return known(exception.code().name());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validation(MethodArgumentNotValidException exception) {
        // rejectedValue taşınmaz: hassas değerler response'a geri yansıtılmaz.
        var fields = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of("field", error.getField(), "message", "Geçersiz değer.")).toList();
        return ResponseEntity.badRequest().body(new ApiError("VALIDATION_FAILED", "Alanları kontrol edin.",
                UUID.randomUUID().toString(), false, Map.of("fields", fields)));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> malformed() {
        return response(400, "INVALID_JSON", false);
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ApiError> duplicate() {
        return response(409, "CONFLICT", false);
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<ApiError> legacy(RuntimeException exception) {
        // B02 saf çekirdeğin mevcut hata kodları korunur; bilinmeyen mesajlar gösterilmez.
        String code = exception.getMessage();
        if (code != null && Set.of("NOT_FOUND", "IDEMPOTENCY_CONFLICT", "VERSION_CONFLICT",
                "PLAN_EXPIRED", "PLAN_REVOKED", "REVALIDATION_REQUIRED", "STALE_MARK", "STALE_QUOTE",
                "INSUFFICIENT_AVAILABLE_BALANCE", "LIQUIDITY_LIMIT", "SESSION_HALTED").contains(code)) {
            return known(code);
        }
        if (code != null && Set.of("INVALID_KEY", "INVALID_SUBMIT", "INVALID_DECIMAL", "DECIMAL_OUT_OF_RANGE",
                "POSITIVE_REQUIRED", "NON_NEGATIVE_REQUIRED", "INVALID_LEVERAGE", "INVALID_HORIZON",
                "INVALID_EXPIRY_POLICY", "INVALID_ORDER_TYPE", "INVALID_TTL", "INVALID_TICK",
                "MIN_NOTIONAL", "LIMIT_PRICE_REQUIRED_OR_FORBIDDEN", "INVALID_APPROVAL_BOUNDS").contains(code)) {
            return response(400, code, false);
        }
        return unexpected(exception); // Programlama hatasını sahte 400 ile gizlemeyiz.
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ApiError> forbidden() {
        return response(403, "FORBIDDEN", false);
    }

    @ExceptionHandler(org.springframework.security.core.AuthenticationException.class)
    public ResponseEntity<ApiError> unauthenticated() {
        return response(401, "UNAUTHORIZED", false);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception exception) {
        String traceId = UUID.randomUUID().toString();
        // Gerçek tracing kurulduğunda aynı traceId MDC/response'a bağlanır.
        // Exception mesajını/SQL'i varsayılan loga dökmeyiz; kontrollü tanılama ayrı tutulur.
        LOG.error("Unexpected failure traceId={} type={}", traceId, exception.getClass().getName());
        return ResponseEntity.internalServerError().body(new ApiError("INTERNAL_ERROR",
                "İşlem tamamlanamadı.", traceId, false, Map.of()));
    }

    private ResponseEntity<ApiError> known(String code) {
        int status = switch (code) {
            case "NOT_FOUND" ->
                404;
            case "IDEMPOTENCY_CONFLICT", "VERSION_CONFLICT", "PLAN_EXPIRED", "PLAN_REVOKED", "REVALIDATION_REQUIRED" ->
                409;
            case "STALE_MARK", "STALE_QUOTE" ->
                503;
            default ->
                422;
        };
        return response(status, code, status == 503);
    }

    private ResponseEntity<ApiError> response(int status, String code, boolean retryable) {
        return ResponseEntity.status(status).body(new ApiError(code, "İstek tamamlanamadı.",
                UUID.randomUUID().toString(), retryable, Map.of()));
    }
}
