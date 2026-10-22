package com.crypto.strategyservice;

import com.crypto.strategyservice.application.port.ModelClient;
import com.crypto.strategyservice.domain.model.PredictionResult;
import com.crypto.strategyservice.infrastructure.client.HttpModelClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class HttpModelClientTest {

    private static final Instant NOW =
            Instant.parse("2026-10-22T10:00:00Z");

    private static final String SECRET = "test-service-secret";
    private static final String HASH = "sha256:" + "b".repeat(64);
    private static final UUID BUNDLE_ID = UUID.randomUUID();

    private final JsonMapper json = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final AtomicReference<CapturedRequest> captured =
            new AtomicReference<>();

    private final AtomicInteger requestCount = new AtomicInteger();

    private HttpServer server;
    private HttpModelClient client;

    private volatile int responseStatus = 200;
    private volatile String responseBody = "{}";

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0),
                0
        );

        server.createContext("/", exchange -> {
            try {
                requestCount.incrementAndGet();

                String body = new String(
                        exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8
                );

                var headers = exchange.getRequestHeaders();

                captured.set(new CapturedRequest(
                        exchange.getRequestMethod(),
                        exchange.getRequestURI().toString(),
                        headers.getFirst("X-Service-Secret"),
                        headers.getFirst("X-Request-Id"),
                        headers.getFirst("X-Deadline"),
                        headers.getFirst("Content-Type"),
                        body
                ));

                exchange.getResponseHeaders().set(
                        "Content-Type",
                        "application/json"
                );

                if (responseStatus == 302) {
                    exchange.getResponseHeaders().set(
                            "Location",
                            "/unexpected"
                    );
                }

                byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(responseStatus, bytes.length);

                try (var output = exchange.getResponseBody()) {
                    output.write(bytes);
                }
            } finally {
                exchange.close();
            }
        });

        server.start();

        URI base = URI.create(
                "http://127.0.0.1:" + server.getAddress().getPort()
        );

        client = new HttpModelClient(
                base,
                SECRET,
                json,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldReadBundleAndAllowAdditionalResponseFields() {
        responseBody = """
                {
                  "bundleId": "%s",
                  "featureVersion": "features-v1",
                  "featureSchemaHash": "%s",
                  "supportedHorizons": [60, 900],
                  "releaseStatus": "DEVELOPMENT_CANDIDATE",
                  "additionalMetadata": {"fixture": true}
                }
                """.formatted(BUNDLE_ID, HASH);

        var bundle = client.activeBundle(900);
        var sent = captured.get();

        assertEquals(BUNDLE_ID, bundle.bundleId());
        assertEquals(List.of(60, 900), bundle.supportedHorizons());
        assertEquals("GET", sent.method());
        assertEquals(
                "/internal/model/active-bundle?horizonSeconds=900",
                sent.path()
        );
    }

    @Test
    void shouldSendPredictionRequestAndReadResponse() {
        var request = predictionRequest();

        var expected = new PredictionResult(
                request.requestId(),
                PredictionResult.Quality.INSUFFICIENT_DATA,
                List.of("TEST_FIXTURE", "INSUFFICIENT_HISTORY"),
                BUNDLE_ID,
                "features-v1",
                HASH,
                UUID.randomUUID(),
                NOW,
                900,
                null,
                null,
                null,
                Map.of(),
                1
        );

        responseBody = json.writeValueAsString(expected);

        var actual = client.predict(request);
        var sent = captured.get();

        assertEquals(expected, actual);
        assertEquals("POST", sent.method());
        assertEquals("/internal/model/predictions", sent.path());
        assertEquals(SECRET, sent.secret());
        assertEquals("application/json", sent.contentType());
        assertEquals(
                NOW.plusSeconds(10),
                Instant.parse(sent.deadline())
        );

        assertNotNull(sent.requestId());
        assertDoesNotThrow(() -> UUID.fromString(sent.requestId()));

        assertEquals(
                request,
                json.readValue(sent.body(), ModelClient.Request.class)
        );
    }

    @Test
    void shouldRejectUnavailableModelResponse() {
        responseStatus = 503;
        responseBody = """
                {"message":"fixture unavailable"}
                """;

        var exception = assertThrows(
                IllegalStateException.class,
                () -> client.predict(predictionRequest())
        );

        assertEquals("MODEL_UNAVAILABLE", exception.getMessage());
        assertEquals("MODEL_HTTP_503", exception.getCause().getMessage());
    }

    @Test
    void shouldRejectMalformedJson() {
        responseBody = "{invalid-json";

        var exception = assertThrows(
                IllegalStateException.class,
                () -> client.predict(predictionRequest())
        );

        assertEquals("MODEL_UNAVAILABLE", exception.getMessage());
        assertNotNull(exception.getCause());
    }

    @Test
    void shouldRejectResponseLargerThanOneMiB() {
        responseBody = "x".repeat(1_048_577);

        var exception = assertThrows(
                IllegalStateException.class,
                () -> client.predict(predictionRequest())
        );

        assertEquals("MODEL_UNAVAILABLE", exception.getMessage());
        assertEquals(
                "MODEL_PAYLOAD_TOO_LARGE",
                exception.getCause().getMessage()
        );
    }

    @Test
    void shouldRejectRedirectWithoutFollowingIt() {
        responseStatus = 302;

        var exception = assertThrows(
                IllegalStateException.class,
                () -> client.predict(predictionRequest())
        );

        assertEquals("MODEL_UNAVAILABLE", exception.getMessage());
        assertEquals("MODEL_HTTP_302", exception.getCause().getMessage());
        assertEquals(1, requestCount.get());
    }

    private ModelClient.Request predictionRequest() {
        return new ModelClient.Request(
                UUID.randomUUID(),
                UUID.randomUUID(),
                900,
                NOW,
                BUNDLE_ID,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "features-v1"
        );
    }

    private record CapturedRequest(
            String method,
            String path,
            String secret,
            String requestId,
            String deadline,
            String contentType,
            String body
    ) {
    }
}