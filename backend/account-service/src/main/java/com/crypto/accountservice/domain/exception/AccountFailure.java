package com.crypto.accountservice.domain.exception;
public class AccountFailure extends RuntimeException {
    public enum Code { INVALID_QUERY, REPORT_UNAVAILABLE, INVALID_REPORT, UNAUTHORIZED, NOT_FOUND }
    private final Code code;
    public AccountFailure(Code code) { super(code.name()); this.code = code; }
    public Code code() { return code; }
}

