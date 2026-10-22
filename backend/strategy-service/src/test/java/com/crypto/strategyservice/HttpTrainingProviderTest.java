package com.crypto.strategyservice;

import com.crypto.strategyservice.application.port.TrainingProvider;
import com.crypto.strategyservice.infrastructure.client.HttpTrainingProvider;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class HttpTrainingProviderTest {

    private static final Instant NOW = Instant.parse("2026-10-22T15:00:00Z");
    private static final String SECRET = "training-internal-secret";
    private static final TrainingProvider.Actor TRAINER_ACTOR = new TrainingProvider.Actor(
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            "TRAINER",
            "MODEL_PUBLISH"
    );

    private record CapturedCall(
            String method,
            String path,
            String secret,
            String actorId,
            String roles,
            String authorities,
            String idempotencyKey,
            String deadline,
            String contentType,
            String body
    ) {
    }

    private final AtomicReference<CapturedCall> captured = new AtomicReference<>();
    private HttpServer server;
    private HttpTrainingProvider provider;
    private volatile int responseStatus = 202;
    private volatile String responseBody = "{\"jobId\":\"33333333-3333-4333-8333-333333333333\",\"status\":\"QUEUED\"}";

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                String reqBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                var h = exchange.getRequestHeaders();
                captured.set(new CapturedCall(
                        exchange.getRequestMethod(),
                        exchange.getRequestURI().toString(),
                        h.getFirst("X-Service-Secret"),
                        h.getFirst("X-Actor-Id"),
                        h.getFirst("X-Actor-Roles"),
                        h.getFirst("X-Actor-Authorities"),
                        h.getFirst("Idempotency-Key"),
                        h.getFirst("X-Deadline"),
                        h.getFirst("Content-Type"),
                        reqBody
                ));

                byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(responseStatus, bytes.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            } finally {
                exchange.close();
            }
        });
        server.start();

        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        provider = new HttpTrainingProvider(base, SECRET, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldForwardJsonWithInternalHeadersAndOnlyReturn202WhenAiReturns202() {
        byte[] payload = "{\"taskType\":\"NUMERIC\"}".getBytes(StandardCharsets.UTF_8);
        var reply = provider.json(TRAINER_ACTOR, "POST", "/runs", "idem-run-1", payload);

        assertEquals(202, reply.status());
        var call = captured.get();
        assertNotNull(call);
        assertEquals("POST", call.method());
        assertEquals("/internal/training/runs", call.path());
        assertEquals(SECRET, call.secret());
        assertEquals(TRAINER_ACTOR.id().toString(), call.actorId());
        assertEquals("TRAINER", call.roles());
        assertEquals("MODEL_PUBLISH", call.authorities());
        assertEquals("idem-run-1", call.idempotencyKey());
        assertEquals(NOW.plusSeconds(30).toString(), call.deadline());
        assertEquals("{\"taskType\":\"NUMERIC\"}", call.body());
    }

    @Test
    void shouldStreamMultipartUploadWithFixedFilenameAndReject5xxOrInvalidPaths() {
        byte[] pdfBytes = "%PDF-1.7 sample content".getBytes(StandardCharsets.UTF_8);
        var reply = provider.upload(
                TRAINER_ACTOR,
                "idem-up-1",
                "Market Microstructure",
                "Author A",
                "Owned educational license",
                "../../evil.pdf",
                pdfBytes.length,
                new ByteArrayInputStream(pdfBytes)
        );

        assertEquals(202, reply.status());
        var call = captured.get();
        assertEquals("POST", call.method());
        assertEquals("/internal/training/sources", call.path());
        assertTrue(call.contentType().startsWith("multipart/form-data; boundary=cryptolab-"));
        assertTrue(call.body().contains("filename=\"source.pdf\""));
        assertFalse(call.body().contains("evil.pdf"));
        assertTrue(call.body().contains("%PDF-1.7 sample content"));

        // Path traversal reddedilmeli
        assertThrows(
                IllegalArgumentException.class,
                () -> provider.json(TRAINER_ACTOR, "GET", "/../secret", null, null)
        );

        // 20MB üzeri dosya reddedilmeli
        assertEquals(
                "UPLOAD_SIZE",
                assertThrows(
                        IllegalArgumentException.class,
                        () -> provider.upload(
                                TRAINER_ACTOR,
                                "idem-big",
                                "Title",
                                null,
                                "Rights",
                                "source.pdf",
                                20L * 1024 * 1024 + 1,
                                new ByteArrayInputStream(new byte[]{1})
                        )
                ).getMessage()
        );

        // AI 503 döndüğünde dışarıya stack trace sızdırmadan TRAINING_UNAVAILABLE fırlatmalı (202 dönmemeli!)
        responseStatus = 503;
        responseBody = "Internal Python Traceback...";
        byte[] runPayload = "{\"taskType\":\"NUMERIC\"}".getBytes(StandardCharsets.UTF_8);
        assertEquals(
                "TRAINING_UNAVAILABLE",
                assertThrows(
                        IllegalStateException.class,
                        () -> provider.json(TRAINER_ACTOR, "POST", "/runs", "idem-run-2", runPayload)
                ).getMessage()
        );
    }
}
