package com.crypto.identityservice.api.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(

        @NotBlank
        @Email
        @Size(max = 254)
        String email,

        @NotBlank
        @Size(min = 12, max = 72)
        String password,

        @NotBlank
        @Size(max = 80)
        String displayName

) {

    @Override
    public String toString() {
        return "RegisterRequest[REDACTED]";
    }
}