package com.crypto.strategyservice.api.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;
import java.util.Set;

@RestControllerAdvice
public class StrategyApiErrors {

    private static final Set<String> SAFE_BAD_REQUEST_CODES = Set.of(
            "UPLOAD_SIZE",
            "BODY_TOO_LARGE",
            "INVALID_SOURCE_METADATA",
            "INVALID_PAGE",
            "INVALID_SESSION_ID",
            "INVALID_INSTRUMENT_ID",
            "INVALID_REQUEST",
            "INVALID_KEY",
            "INVALID_CURSOR",
            "INVALID_URL"
    );

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> accessDenied(AccessDeniedException ex) {
        if ("UNAUTHORIZED".equals(ex.getMessage())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ErrorResponses.body("UNAUTHORIZED", false));
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponses.body("FORBIDDEN", false));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> maxUploadSize(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ErrorResponses.body("PAYLOAD_TOO_LARGE", false));
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingRequestHeaderException.class,
            MissingServletRequestParameterException.class
    })
    public ResponseEntity<Map<String, Object>> invalidRequest(Exception ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponses.body("INVALID_REQUEST", false));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponses.body("NOT_FOUND", false));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> unsupportedMedia(HttpMediaTypeNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(ErrorResponses.body("UNSUPPORTED_MEDIA_TYPE", false));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> methodNotAllowed(HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ErrorResponses.body("METHOD_NOT_ALLOWED", false));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException ex) {
        String code = ex.getMessage();
        if ("UPLOAD_SIZE".equals(code) || "BODY_TOO_LARGE".equals(code)) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(ErrorResponses.body("PAYLOAD_TOO_LARGE", false));
        }
        String safeCode = (code != null && SAFE_BAD_REQUEST_CODES.contains(code)) ? code : "INVALID_REQUEST";
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponses.body(safeCode, false));
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

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponses.body("INTERNAL_ERROR", true));
    }
}

