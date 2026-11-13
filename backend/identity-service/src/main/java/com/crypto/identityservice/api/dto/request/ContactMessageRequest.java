package com.crypto.identityservice.api.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ContactMessageRequest(
        @NotBlank
        @Size(max = 120)
        String senderName,

        @NotBlank
        @Email
        @Size(max = 254)
        String senderEmail,

        @NotBlank
        @Size(max = 200)
        String subject,

        @NotBlank
        @Size(max = 4000)
        String message,

        @Size(max = 120)
        String recipientEmail
) {
}
