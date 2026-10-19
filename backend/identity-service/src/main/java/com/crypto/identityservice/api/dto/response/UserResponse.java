package com.crypto.identityservice.api.dto.response;

import java.util.List;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String displayName,
        List<String> roles
) {

    public UserResponse {
        roles = List.copyOf(roles);
    }
}