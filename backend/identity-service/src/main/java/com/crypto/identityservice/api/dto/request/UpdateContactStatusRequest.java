package com.crypto.identityservice.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record UpdateContactStatusRequest(
        @NotBlank
        @Pattern(regexp = "NEW|READ|RESOLVED")
        String status
) {
}
