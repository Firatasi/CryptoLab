package com.crypto.accountservice.api.dto.response;
import java.util.Map;
public record AccountError(String code,String message,String traceId,boolean retryable,Map<String,Object> details) {
    public AccountError { details=Map.copyOf(details); }
}

