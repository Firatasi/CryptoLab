package com.crypto.accountservice.api.exception;
import com.crypto.accountservice.domain.exception.AccountFailure;
import com.crypto.accountservice.api.dto.response.AccountError;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import java.util.Map;
import java.util.UUID;
@RestControllerAdvice
public class AccountErrors {
    @ExceptionHandler(AccountFailure.class)
    public ResponseEntity<AccountError> business(AccountFailure e) {
        int status=switch(e.code()) {
            case INVALID_QUERY -> 400;
            case UNAUTHORIZED -> 401;
            case NOT_FOUND -> 404;
            case REPORT_UNAVAILABLE -> 503;
            case INVALID_REPORT -> 502;
        };
        return response(status,e.code().name(),status==503);
    }
    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingRequestHeaderException.class})
    public ResponseEntity<AccountError> malformed() { return response(400,"INVALID_QUERY",false); }
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<AccountError> notFound() { return response(404,"NOT_FOUND",false); }
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<AccountError> methodNotAllowed() { return response(405,"METHOD_NOT_ALLOWED",false); }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<AccountError> unexpected() { return response(500,"INTERNAL_ERROR",false); }
    private ResponseEntity<AccountError> response(int status,String code,boolean retry) {
        return ResponseEntity.status(status).header("Cache-Control","no-store").body(new AccountError(code,
                "İstek tamamlanamadı.",UUID.randomUUID().toString(),retry,Map.of()));
    }
}

