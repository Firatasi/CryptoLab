package com.crypto.identityservice.api.exception;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

@RestControllerAdvice
public class IdentityExceptionHandler {

    private static final Logger log =
            LoggerFactory.getLogger(IdentityExceptionHandler.class);

    private ResponseEntity<Map<String, Object>> error(
            int status,
            String code
    ) {
        return ResponseEntity.status(status)
                .header("Cache-Control", "no-store")
                .body(ErrorResponses.body(code));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<?> duplicate(DuplicateKeyException exception) {
        return error(409, "CONFLICT");
    }

    @ExceptionHandler(EmptyResultDataAccessException.class)
    public ResponseEntity<?> missing(EmptyResultDataAccessException exception) {
        return error(404, "NOT_FOUND");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<?> forbidden(AccessDeniedException exception) {
        return error(403, "FORBIDDEN");
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            MissingRequestHeaderException.class,
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<?> invalidRequest(Exception exception) {
        return error(400, "INVALID_REQUEST");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<?> unsupportedMediaType(
            HttpMediaTypeNotSupportedException exception
    ) {
        return error(415, "UNSUPPORTED_MEDIA_TYPE");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> invalidArgument(
            IllegalArgumentException exception
    ) {
        if ("INVALID_CREDENTIALS".equals(exception.getMessage())) {
            return error(401, "INVALID_CREDENTIALS");
        }

        return error(400, "INVALID_REQUEST");
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<?> invalidState(
            IllegalStateException exception
    ) {
        if ("RATE_LIMITED".equals(exception.getMessage())) {
            return error(429, "RATE_LIMITED");
        }

        if ("IDEMPOTENCY_CONFLICT".equals(exception.getMessage())) {
            return error(409, "IDEMPOTENCY_CONFLICT");
        }

        log.error("Identity isleminde beklenmeyen durum", exception);
        return error(500, "INTERNAL_ERROR");    }



    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> unexpected(Exception exception) {
        log.error("Identity isleminde beklenmeyen hata", exception);

        return error(500, "INTERNAL_ERROR");
    }
}