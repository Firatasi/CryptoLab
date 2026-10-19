package com.crypto.identityservice.api.dto.response;

public record TokenResponse(
        String accessToken,
        long expiresIn
) {

    @Override
    public String toString() {
        return "TokenResponse[REDACTED]";
    }
}