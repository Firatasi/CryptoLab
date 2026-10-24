package com.crypto.accountservice.api.exception;
import com.crypto.accountservice.domain.exception.AccountFailure;
import com.crypto.accountservice.api.dto.response.AccountError;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
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
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<AccountError> malformed() { return response(400,"INVALID_QUERY",false); }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<AccountError> unexpected() { return response(500,"INTERNAL_ERROR",false); }
    private ResponseEntity<AccountError> response(int status,String code,boolean retry) {
        return ResponseEntity.status(status).header("Cache-Control","no-store").body(new AccountError(code,
                "İstek tamamlanamadı.",UUID.randomUUID().toString(),retry,Map.of()));
    }
}

