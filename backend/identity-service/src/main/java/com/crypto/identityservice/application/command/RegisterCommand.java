package com.crypto.identityservice.application.command;

import java.util.UUID;

public record RegisterCommand(
        String email,
        String displayName,
        String password,
        UUID captchaId,
        String captchaAnswer
) {

    public RegisterCommand {
        // HTTP dışında çağrılsa da temel doğrulamaları uygula.
        if (email == null || email.isBlank() || email.length() > 254
                || displayName == null || displayName.isBlank()
                || displayName.length() > 80
                || password == null || password.isBlank()
                || password.length() < 12 || password.length() > 72
                || captchaId == null
                || captchaAnswer == null || captchaAnswer.isBlank() || captchaAnswer.length() > 16) {
            throw new IllegalArgumentException("INVALID_REGISTRATION");
        }
    }

    @Override
    public String toString() {
        return "RegisterCommand[REDACTED]";
    }
}