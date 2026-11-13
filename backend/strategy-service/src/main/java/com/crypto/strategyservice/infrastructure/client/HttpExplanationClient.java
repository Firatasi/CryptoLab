package com.crypto.strategyservice.infrastructure.client;

import com.crypto.strategyservice.application.port.ExplanationClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Calls the AI service /internal/model/explanations endpoint so validated numeric decisions
 * receive structured Turkish technical indicator & risk rationale. Failures safely fall back
 * to ExplanationTemplate inside AnalysisWorker without extending proposal TTL.
 */
@Component
@Primary
@Profile("!fixture & !test")
public class HttpExplanationClient implements ExplanationClient {

    private static final Logger log = LoggerFactory.getLogger(HttpExplanationClient.class);

    private final URI baseUri;
    private final String serviceSecret;
    private final HttpClient httpClient;
    private final JsonMapper json;
    private final Clock clock;

    public HttpExplanationClient(
            @Value("${model.base-url:http://127.0.0.1:8091}") String baseUrl,
            @Value("${model.service-secret:cryptolab-secret-model-service-32bytes-min}") String serviceSecret,
            JsonMapper json,
            Clock clock
    ) {
        this.baseUri = URI.create(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
        this.serviceSecret = serviceSecret;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        this.json = json;
        this.clock = clock;
    }

    @Override
    public String explain(Request request) {
        if (request.predictionResult() == null || request.modelBundleId() == null) {
            throw new IllegalStateException("MISSING_EXPLANATION_CONTEXT");
        }
        try {
            Map<String, Object> policyMap = new LinkedHashMap<>();
            policyMap.put("action", request.action());
            policyMap.put("reasonCode", request.policyReason());
            policyMap.put("reasonCodes", request.reasonCodes());
            if (request.policyOutcome() != null) {
                policyMap.put("stopLoss", request.policyOutcome().stopLoss());
                policyMap.put("takeProfit", request.policyOutcome().takeProfit());
                policyMap.put("suggestedLeverage", request.policyOutcome().suggestedLeverage());
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("requestId", request.analysisId());
            payload.put("modelBundleId", request.modelBundleId());
            payload.put("predictionResult", request.predictionResult());
            payload.put("validatedPolicyOutcome", policyMap);
            payload.put("verifiedFeatureSummary", request.observedFeatureSummary());
            payload.put("language", "tr");

            Instant deadline = clock.instant().plusSeconds(4);
            HttpRequest httpReq = HttpRequest.newBuilder()
                    .uri(baseUri.resolve("/internal/model/explanations"))
                    .timeout(Duration.ofSeconds(4))
                    .header("Content-Type", "application/json")
                    .header("X-Service-Secret", serviceSecret)
                    .header("X-Request-Id", UUID.randomUUID().toString())
                    .header("X-Deadline", deadline.toString())
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)))
                    .build();

            HttpResponse<String> resp = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("Explanation API returned HTTP {}: {}", resp.statusCode(), resp.body());
                throw new IllegalStateException("EXPLANATION_HTTP_" + resp.statusCode());
            }

            JsonNode root = json.readTree(resp.body());
            String text = root.path("text").asText("");
            if (text.isBlank()) {
                throw new IllegalStateException("EMPTY_EXPLANATION");
            }
            return text;
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("EXPLANATION_UNAVAILABLE", ex);
        }
    }
}
