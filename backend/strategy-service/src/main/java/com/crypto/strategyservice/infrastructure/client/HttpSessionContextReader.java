package com.crypto.strategyservice.infrastructure.client;

import com.crypto.strategyservice.application.port.SessionContextReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Queries simulation-service over HTTP (forwarding the caller's Bearer token)
 * so Strategy enforces real ownership, session status, generation, and riskPolicyId
 * without cross-service database queries.
 */
@Component
@Primary
@Profile("!fixture & !test")
public class HttpSessionContextReader implements SessionContextReader {

    private static final Logger log = LoggerFactory.getLogger(HttpSessionContextReader.class);

    // Binance USD-M Futures realistic taker round-trip fee + slippage (~12 bps)
    private static final double LIVE_ROUND_TRIP_COST = 0.0012;

    private final URI baseUri;
    private final HttpClient httpClient;
    private final JsonMapper json;
    private final Map<UUID, SessionRiskProfile> cachedProfiles = new ConcurrentHashMap<>();

    public HttpSessionContextReader(
            @Value("${simulation.service.url:http://127.0.0.1:8083}") String baseUrl,
            JsonMapper json
    ) {
        this.baseUri = URI.create(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        this.json = json;
    }

    @Override
    public Optional<SessionSnapshot> findOwnedSession(UUID ownerId, UUID sessionId, UUID instrumentId) {
        if (ownerId == null || sessionId == null || instrumentId == null) {
            return Optional.empty();
        }
        Optional<JsonNode> sessionJson = fetchSessionJson(sessionId);
        if (sessionJson.isEmpty()) {
            return Optional.empty();
        }
        JsonNode root = sessionJson.get();
        String status = root.path("status").asText("RUNNING");
        long generation = root.path("generation").asLong(1L);
        UUID riskPolicyId = parseUuidOrDefault(
                root.path("riskPolicyId").asText(null),
                SessionRiskProfile.BALANCED_POLICY_ID
        );
        cachedProfiles.put(sessionId, new SessionRiskProfile(riskPolicyId, resolveMaxLeverage(riskPolicyId)));
        return Optional.of(new SessionSnapshot(
                sessionId,
                ownerId,
                instrumentId,
                generation,
                status,
                LIVE_ROUND_TRIP_COST
        ));
    }

    @Override
    public SessionRiskProfile findSessionRiskProfile(UUID ownerId, UUID sessionId) {
        if (sessionId == null) {
            return SessionContextReader.super.findSessionRiskProfile(ownerId, null);
        }
        SessionRiskProfile cached = cachedProfiles.get(sessionId);
        if (cached != null) {
            return cached;
        }
        Optional<JsonNode> sessionJson = fetchSessionJson(sessionId);
        if (sessionJson.isPresent()) {
            UUID riskPolicyId = parseUuidOrDefault(
                    sessionJson.get().path("riskPolicyId").asText(null),
                    SessionRiskProfile.BALANCED_POLICY_ID
            );
            SessionRiskProfile resolved = new SessionRiskProfile(riskPolicyId, resolveMaxLeverage(riskPolicyId));
            cachedProfiles.put(sessionId, resolved);
            return resolved;
        }
        return SessionContextReader.super.findSessionRiskProfile(ownerId, sessionId);
    }

    private Optional<JsonNode> fetchSessionJson(UUID sessionId) {
        String authHeader = currentAuthorizationHeader();
        if (authHeader == null || authHeader.isBlank()) {
            log.warn("No Authorization header on request thread while verifying session {}", sessionId);
            return Optional.empty();
        }
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(baseUri.resolve("/api/v1/sim/sessions/" + sessionId))
                    .timeout(Duration.ofSeconds(3))
                    .header(HttpHeaders.AUTHORIZATION, authHeader)
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                return Optional.of(json.readTree(resp.body()));
            }
            if (resp.statusCode() == 404 || resp.statusCode() == 403 || resp.statusCode() == 401) {
                return Optional.empty();
            }
            log.warn("simulation-service returned HTTP {} for session {}", resp.statusCode(), sessionId);
            return Optional.empty();
        } catch (Exception ex) {
            log.warn("Failed to query simulation-service for session {}: {}", sessionId, ex.getMessage());
            return Optional.empty();
        }
    }

    private static String currentAuthorizationHeader() {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes servletAttrs) {
            return servletAttrs.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
        }
        return null;
    }

    private static UUID parseUuidOrDefault(String raw, UUID fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    public static int resolveMaxLeverage(UUID riskPolicyId) {
        if (SessionRiskProfile.CONSERVATIVE_POLICY_ID.equals(riskPolicyId)) {
            return 5;
        }
        if (SessionRiskProfile.ADVANCED_POLICY_ID.equals(riskPolicyId)) {
            return 50;
        }
        return 20;
    }
}
