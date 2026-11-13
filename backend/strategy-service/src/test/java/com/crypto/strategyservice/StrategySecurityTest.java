package com.crypto.strategyservice;

import com.crypto.strategyservice.api.controller.StrategyController;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class StrategySecurityTest {

    @Test
    void createAnalysis_rejectsMissingJwtAndBlankSessionId() {
        StrategyController controller = new StrategyController(null, null, null, Clock.systemUTC());
        var req = new StrategyController.CreateAnalysisRequest(
                UUID.randomUUID().toString(),
                "BTCUSDT",
                3600,
                Instant.now(),
                "MEDIUM"
        );
        assertThrows(AccessDeniedException.class, () -> controller.createAnalysis(null, "idem-1", req));

        Jwt jwt = Jwt.withTokenValue("tok")
                .header("alg", "RS256")
                .subject(UUID.randomUUID().toString())
                .claim("roles", List.of("USER"))
                .claim("authorities", List.of())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        var blankSessionReq = new StrategyController.CreateAnalysisRequest(
                "   ",
                "BTCUSDT",
                3600,
                Instant.now(),
                "MEDIUM"
        );
        assertThrows(IllegalArgumentException.class, () -> controller.createAnalysis(jwt, "idem-2", blankSessionReq));
    }
}
