package com.crypto.strategyservice.api.controller;

import com.crypto.strategyservice.api.validation.ContractGate;
import com.crypto.strategyservice.application.port.TrainingProvider;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Sorumluluk: HTTP isteğini uygulama akışına bağlar.
 * Okuma ipucu: DTO doğrulamasını, principal kaynağını ve HTTP cevabını takip et; SQL burada bulunmaz.
 */
@RestController
@RequestMapping("/api/v1/strategy/training")
@PreAuthorize("hasAnyRole('TRAINER','ADMIN')")
public class TrainingJsonController {

    private final TrainingProvider provider;
    private final ContractGate contracts;

    public TrainingJsonController(TrainingProvider provider, ContractGate contracts) {
        this.provider = provider;
        this.contracts = contracts;
    }

    private ResponseEntity<byte[]> forward(
            Jwt jwt,
            String method,
            String path,
            String key,
            byte[] body,
            Map<String, String> query
    ) {
        // ContractGate ilgili JSON Schema'yı çalıştırır; generic proxy doğrulamayı kaldırmaz.
        contracts.validate(method, path, body);
        var roles = jwt.getClaimAsStringList("roles");
        var auth = jwt.getClaimAsStringList("authorities");
        var actor = new TrainingProvider.Actor(
                UUID.fromString(jwt.getSubject()),
                roles == null ? "" : String.join(",", roles),
                auth == null ? "" : String.join(",", auth)
        );
        var allowed = Set.of("cursor", "datasetId", "expectedVersion");
        var parts = new ArrayList<String>();
        query.forEach((k, v) -> {
            if (!allowed.contains(k)) {
                throw new IllegalArgumentException("UNKNOWN_QUERY");
            }
            parts.add(URLEncoder.encode(k, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8));
        });
        var result = provider.json(
                actor,
                method,
                path + (parts.isEmpty() ? "" : "?" + String.join("&", parts)),
                key,
                body
        );
        return ResponseEntity.status(result.status())
                .contentType(MediaType.parseMediaType(result.contentType()))
                .body(result.body());
    }

    @PostMapping("/source-imports")
    public ResponseEntity<byte[]> sourcesImport(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody(required = false) byte[] body
    ) {
        return forward(jwt, "POST", "/source-imports", key, body, Map.of());
    }

    @GetMapping("/sources")
    public ResponseEntity<byte[]> sources(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam Map<String, String> query
    ) {
        return forward(jwt, "GET", "/sources", null, null, query);
    }

    @GetMapping("/sources/{id}")
    public ResponseEntity<byte[]> source(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestParam Map<String, String> query
    ) {
        return forward(jwt, "GET", "/sources/" + id, null, null, query);
    }

    @DeleteMapping("/sources/{id}")
    public ResponseEntity<byte[]> deleteSource(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody(required = false) byte[] body
    ) {
        return forward(jwt, "DELETE", "/sources/" + id, key, body, Map.of());
    }

    @PostMapping("/example-jobs")
    public ResponseEntity<byte[]> examplesJob(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody(required = false) byte[] body
    ) {
        return forward(jwt, "POST", "/example-jobs", key, body, Map.of());
    }

    @GetMapping("/examples")
    public ResponseEntity<byte[]> examples(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam Map<String, String> query
    ) {
        return forward(jwt, "GET", "/examples", null, null, query);
    }

    @PatchMapping("/examples/{id}")
    public ResponseEntity<byte[]> review(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody(required = false) byte[] body
    ) {
        return forward(jwt, "PATCH", "/examples/" + id, key, body, Map.of());
    }

    @PostMapping("/datasets")
    public ResponseEntity<byte[]> dataset(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody(required = false) byte[] body
    ) {
        return forward(jwt, "POST", "/datasets", key, body, Map.of());
    }

    @PostMapping("/runs")
    public ResponseEntity<byte[]> run(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody(required = false) byte[] body
    ) {
        return forward(jwt, "POST", "/runs", key, body, Map.of());
    }

    @GetMapping("/runs/{id}")
    public ResponseEntity<byte[]> runDetail(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestParam Map<String, String> query
    ) {
        return forward(jwt, "GET", "/runs/" + id, null, null, query);
    }

    @PostMapping("/runs/{id}/cancel")
    public ResponseEntity<byte[]> cancel(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody(required = false) byte[] body
    ) {
        return forward(jwt, "POST", "/runs/" + id + "/cancel", key, body, Map.of());
    }

    @GetMapping("/models")
    public ResponseEntity<byte[]> models(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam Map<String, String> query
    ) {
        return forward(jwt, "GET", "/models", null, null, query);
    }

    @GetMapping("/models/{id}")
    public ResponseEntity<byte[]> model(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestParam Map<String, String> query
    ) {
        return forward(jwt, "GET", "/models/" + id, null, null, query);
    }
}

