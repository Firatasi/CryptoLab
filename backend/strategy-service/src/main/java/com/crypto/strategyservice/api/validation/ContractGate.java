package com.crypto.strategyservice.api.validation;

/** Bir route'un mevcut JSON Schema sözleşmesini uygulayan doğrulama sınırı. */
public interface ContractGate {

    void validate(String method, String path, byte[] body);
    // Schema validator adaptörü yazılmadan endpoint hazır sayılmaz.
    // Bu interface örneği tek başına çalışan bean üretmez.
}

