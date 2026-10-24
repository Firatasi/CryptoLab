package com.crypto.strategyservice.infrastructure.client;

import com.crypto.strategyservice.application.port.TrainingProvider;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sorumluluk: Dış sistem veya teknik sınırı uygular.
 * Okuma ipucu: Timeout, serialization, hata/fallback ve kaynak kapatma davranışını incele.
 */
public class HttpTrainingProvider implements TrainingProvider {

    private final URI base;
    private final String secret;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public HttpTrainingProvider(URI base, String secret) {
        this(base, secret, Clock.systemUTC());
    }

    public HttpTrainingProvider(URI base, String secret, Clock clock) {
        if (base == null
                || base.getHost() == null
                || !("http".equalsIgnoreCase(base.getScheme()) || "https".equalsIgnoreCase(base.getScheme()))
                || base.getUserInfo() != null) {
            throw new IllegalArgumentException("INVALID_TRAINING_BASE_URL");
        }
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("MISSING_TRAINING_SERVICE_SECRET");
        }
        this.base = base;
        this.secret = secret;
        this.clock = clock;
    }

    private HttpRequest.Builder request(Actor actor, String path, String key) {
        if (path == null || !path.startsWith("/") || path.contains("..") || path.contains("://")) {
            throw new IllegalArgumentException("INVALID_INTERNAL_PATH");
        }
        var b = HttpRequest.newBuilder(base.resolve("/internal/training" + path))
                .timeout(Duration.ofSeconds(30))
                .header("X-Service-Secret", secret)
                .header("X-Actor-Id", actor.id().toString())
                .header("X-Actor-Roles", actor.roles())
                .header("X-Actor-Authorities", actor.authorities())
                .header("X-Request-Id", UUID.randomUUID().toString())
                .header("X-Deadline", clock.instant().plusSeconds(30).toString());
        if (key != null) {
            if (key.isBlank() || key.length() > 128) {
                throw new IllegalArgumentException("INVALID_KEY");
            }
            b.header("Idempotency-Key", key);
        }
        return b;
    }

    private Reply send(HttpRequest request) {
        try {
            var r = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var in = r.body()) {
                byte[] body = in.readNBytes(1_048_577);
                if (body.length > 1_048_576) {
                    throw new IllegalStateException("TRAINING_RESPONSE_TOO_LARGE");
                }
                // İç hata stack trace'ini dışarı taşımak yerine API zarfına map et.
                if (r.statusCode() >= 500) {
                    throw new IllegalStateException("TRAINING_UNAVAILABLE");
                }
                return new Reply(
                        r.statusCode(),
                        r.headers().firstValue("Content-Type").orElse("application/json"),
                        body
                );
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("TRAINING_CANCELLED", e);
        } catch (IOException e) {
            throw new IllegalStateException("TRAINING_UNAVAILABLE", e);
        }
    }

    @Override
    public Reply json(Actor actor, String method, String path, String key, byte[] body) {
        if (body != null && body.length > 1_048_576) {
            throw new IllegalArgumentException("BODY_TOO_LARGE");
        }
        return send(request(actor, path, key)
                .header("Content-Type", "application/json")
                .method(
                        method,
                        body == null || body.length == 0
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofByteArray(body)
                )
                .build());
    }

    private static byte[] field(String boundary, String name, String value) {
        return ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public Reply upload(
            Actor actor,
            String key,
            String title,
            String author,
            String rights,
            String filename,
            long length,
            InputStream content
    ) {
        String boundary = "cryptolab-" + UUID.randomUUID();
        if (length <= 0 || length > 20L * 1024 * 1024) {
            throw new IllegalArgumentException("UPLOAD_SIZE");
        }
        List<InputStream> parts = new ArrayList<>();
        parts.add(new ByteArrayInputStream(field(boundary, "title", title)));
        if (author != null) {
            parts.add(new ByteArrayInputStream(field(boundary, "author", author)));
        }
        parts.add(new ByteArrayInputStream(field(boundary, "rightsDeclaration", rights)));
        String head = "--" + boundary
                + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"source.pdf\"\r\nContent-Type: application/pdf\r\n\r\n";
        parts.add(new ByteArrayInputStream(head.getBytes(StandardCharsets.UTF_8)));
        // Dosyayı getBytes() ile belleğe toplamayız. Servlet ve facade sınırı önceden uygulanır.
        parts.add(content);
        parts.add(new ByteArrayInputStream(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8)));
        var stream = new SequenceInputStream(Collections.enumeration(parts));
        var used = new AtomicBoolean();
        var publisher = HttpRequest.BodyPublishers.ofInputStream(() -> {
            if (!used.compareAndSet(false, true)) {
                throw new IllegalStateException("NON_REPEATABLE_UPLOAD");
            }
            return stream;
        });
        return send(request(actor, "/sources", key)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(publisher)
                .build());
    }
}

