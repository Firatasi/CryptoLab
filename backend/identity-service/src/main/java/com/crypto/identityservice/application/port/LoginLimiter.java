package com.crypto.identityservice.application.port;

public interface LoginLimiter {

    void check(String key);
}