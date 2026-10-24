package com.crypto.strategyservice.infrastructure.client;

import com.crypto.strategyservice.application.port.ModelClient;
import com.crypto.strategyservice.domain.model.PredictionResult;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

public class HttpModelClient implements ModelClient {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_RESPONSE_BYTES = 1_048_576;

    private final URI base;
    private final String secret;
    private final JsonMapper json;
    private final Clock clock;
    private final HttpClient client;

    public HttpModelClient(
            URI base,
            String secret,
            JsonMapper json,
            Clock clock
    ) {
        if (base == null
                || base.getHost() == null
                || !("http".equalsIgnoreCase(base.getScheme())
                || "https".equalsIgnoreCase(base.getScheme()))
                || base.getUserInfo() != null) {
            throw new IllegalArgumentException("INVALID_MODEL_BASE_URL");
        }

        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("MISSING_MODEL_SERVICE_SECRET");
        }

        this.base = base;
        this.secret = secret;
        this.json = json;
        this.clock = clock;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public Bundle activeBundle(int horizonSeconds) {
        return send(
                "/internal/model/active-bundle?horizonSeconds="
                        + horizonSeconds,
                null,
                Bundle.class
        );
    }

    @Override
    public PredictionResult predict(Request request) {
        return send(
                "/internal/model/predictions",
                request,
                PredictionResult.class
        );
    }

    private <T> T send(
            String path,
            Object body,
            Class<T> responseType
    ) {
        try {
            var builder = HttpRequest.newBuilder(base.resolve(path))
                    .timeout(REQUEST_TIMEOUT)
                    .header("X-Service-Secret", secret)
                    .header("X-Request-Id", UUID.randomUUID().toString())
                    .header(
                            "X-Deadline",
                            clock.instant()
                                    .plus(REQUEST_TIMEOUT)
                                    .toString()
                    )
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json");

            if (body == null) {
                builder.GET();
            } else {
                builder.POST(
                        HttpRequest.BodyPublishers.ofByteArray(
                                json.writeValueAsBytes(body)
                        )
                );
            }

            var response = client.send(
                    builder.build(),
                    HttpResponse.BodyHandlers.ofInputStream()
            );

            try (var input = response.body()) {
                if (response.statusCode() != 200) {
                    throw new IllegalStateException(
                            "MODEL_HTTP_" + response.statusCode()
                    );
                }

                byte[] bytes = input.readNBytes(MAX_RESPONSE_BYTES + 1);

                if (bytes.length > MAX_RESPONSE_BYTES) {
                    throw new IllegalStateException(
                            "MODEL_PAYLOAD_TOO_LARGE"
                    );
                }

                T result = json.readValue(bytes, responseType);

                if (result == null) {
                    throw new IllegalStateException("MODEL_EMPTY_RESPONSE");
                }

                return result;
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("MODEL_CANCELLED", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("MODEL_UNAVAILABLE", exception);
        }
    }
}