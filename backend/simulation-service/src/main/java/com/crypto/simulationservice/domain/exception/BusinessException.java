package com.crypto.simulationservice.domain.exception;

public class BusinessException extends RuntimeException {

    public enum Code {
        NOT_FOUND, IDEMPOTENCY_CONFLICT, VERSION_CONFLICT, PLAN_EXPIRED,
        PLAN_REVOKED, REVALIDATION_REQUIRED, STALE_MARK, STALE_QUOTE,
        INSUFFICIENT_AVAILABLE_BALANCE, LIQUIDITY_LIMIT, SESSION_HALTED
    }
    private final Code code;

    public BusinessException(Code code) {
        super(code.name()); // Sadece güvenli kod; SQL veya request body taşınmaz.
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
