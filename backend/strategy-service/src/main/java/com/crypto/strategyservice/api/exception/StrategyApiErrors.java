package com.crypto.strategyservice.api.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;

@RestControllerAdvice
public class StrategyApiErrors {

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> accessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponses.body("FORBIDDEN", false));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> maxUploadSize(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ErrorResponses.body("PAYLOAD_TOO_LARGE", false));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException ex) {
        String code = ex.getMessage() != null ? ex.getMessage() : "INVALID_REQUEST";
        if ("UPLOAD_SIZE".equals(code) || "BODY_TOO_LARGE".equals(code)) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(ErrorResponses.body("PAYLOAD_TOO_LARGE", false));
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponses.body(code, false));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> illegalState(IllegalStateException ex) {
        String code = ex.getMessage() != null ? ex.getMessage() : "INTERNAL_ERROR";
        return switch (code) {
            case "IDEMPOTENCY_CONFLICT", "ALIAS_VERSION_CONFLICT" ->
                    ResponseEntity.status(HttpStatus.CONFLICT)
                            .body(ErrorResponses.body(code, false));
            case "TRAINING_UNAVAILABLE", "TRAINING_CANCELLED", "TRAINING_RESPONSE_TOO_LARGE" ->
                    ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                            .body(ErrorResponses.body("TRAINING_UNAVAILABLE", true));
            case "SESSION_NOT_FOUND", "SCAN_NOT_FOUND" ->
                    ResponseEntity.status(HttpStatus.NOT_FOUND)
                            .body(ErrorResponses.body("NOT_FOUND", false));
            default ->
                    ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(ErrorResponses.body("INTERNAL_ERROR", false));
        };
    }
}

