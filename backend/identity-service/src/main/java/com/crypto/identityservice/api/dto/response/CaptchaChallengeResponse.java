package com.crypto.identityservice.api.dto.response;

import java.util.UUID;

public record CaptchaChallengeResponse(
        UUID captchaId,
        String imageDataUri,
        int expiresInSeconds
) {
}
