package com.crypto.strategyservice.api.controller;

import com.crypto.strategyservice.application.port.TrainingProvider;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Set;
import java.util.UUID;

/**
 * Sorumluluk: HTTP isteğini uygulama akışına bağlar.
 * Okuma ipucu: DTO doğrulamasını, principal kaynağını ve HTTP cevabını takip et; SQL burada bulunmaz.
 */
@RestController
@RequestMapping("/api/v1/strategy/training")
@PreAuthorize("hasAnyRole('TRAINER','ADMIN')")
public class TrainingController {

    private final TrainingProvider provider;
    private final JsonMapper json;

    public TrainingController(TrainingProvider provider, JsonMapper json) {
        this.provider = provider;
        this.json = json;
    }

    private TrainingProvider.Actor actor(Jwt jwt) {
        var roles = jwt.getClaimAsStringList("roles");
        var rights = jwt.getClaimAsStringList("authorities");
        return new TrainingProvider.Actor(
                UUID.fromString(jwt.getSubject()),
                roles == null ? "" : String.join(",", roles),
                rights == null ? "" : String.join(",", rights)
        );
    }

    private ResponseEntity<byte[]> response(TrainingProvider.Reply reply) {
        // Status/DTO schema doğrulaması adapter'da; arbitrary header veya signed URL geçirme.
        return ResponseEntity.status(reply.status())
                .contentType(MediaType.parseMediaType(reply.contentType()))
                .body(reply.body());
    }

    @PostMapping(value = "/sources", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> upload(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key,
            @RequestPart("file") MultipartFile file,
            @RequestParam String title,
            @RequestParam(required = false) String author,
            @RequestParam String rightsDeclaration
    ) throws Exception {
        if (file.isEmpty() || file.getSize() > 20L * 1024 * 1024) {
            throw new IllegalArgumentException("UPLOAD_SIZE");
        }
        if (title.isBlank() || title.length() > 200
                || rightsDeclaration.isBlank() || rightsDeclaration.length() > 4000) {
            throw new IllegalArgumentException("INVALID_SOURCE_METADATA");
        }
        // Filename kullanıcı girdisidir; path olarak kullanılmaz. Sunucu sabit aktarım adı seçer.
        try (var stream = file.getInputStream()) {
            return response(provider.upload(
                    actor(jwt),
                    key,
                    title,
                    author,
                    rightsDeclaration,
                    "source.pdf",
                    file.getSize(),
                    stream
            ));
        }
    }

    @GetMapping("/sources/{id}/pages/{page}")
    public ResponseEntity<byte[]> sourcePage(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @PathVariable int page
    ) {
        if (page < 1) {
            throw new IllegalArgumentException("INVALID_PAGE");
        }
        return response(provider.json(
                actor(jwt),
                "GET",
                "/sources/" + id + "/pages/" + page,
                null,
                null
        ));
    }

    @PostMapping("/models/{id}/promote")
    @PreAuthorize("hasAnyRole('TRAINER','ADMIN') and hasAuthority('MODEL_PUBLISH')")
    public ResponseEntity<byte[]> promote(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody JsonNode body
    ) {
        requireFields(body, Set.of("expectedAliasVersion", "evaluationId"));
        validateAliasVersion(body.get("expectedAliasVersion"));
        validateUuid(body.get("evaluationId"), "INVALID_EVALUATION_ID");
        return response(provider.json(
                actor(jwt),
                "POST",
                "/models/" + id + "/promote",
                key,
                json.writeValueAsBytes(body)
        ));
    }

    @PostMapping("/models/{id}/rollback")
    @PreAuthorize("hasAnyRole('TRAINER','ADMIN') and hasAuthority('MODEL_PUBLISH')")
    public ResponseEntity<byte[]> rollback(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody JsonNode body
    ) {
        requireFields(body, Set.of("expectedAliasVersion", "reason"));
        validateAliasVersion(body.get("expectedAliasVersion"));
        JsonNode reason = body.get("reason");
        if (reason == null || !reason.isString() || reason.asString().isBlank() || reason.asString().length() > 500) {
            throw new IllegalArgumentException("INVALID_REASON");
        }
        return response(provider.json(
                actor(jwt),
                "POST",
                "/models/" + id + "/rollback",
                key,
                json.writeValueAsBytes(body)
        ));
    }

    public static void requireFields(JsonNode node, Set<String> allowed) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("INVALID_BODY");
        }
        for (String f : node.propertyNames()) {
            if (!allowed.contains(f)) {
                throw new IllegalArgumentException("UNKNOWN_FIELD");
            }
        }
        for (String f : allowed) {
            if (!node.hasNonNull(f)) {
                throw new IllegalArgumentException("MISSING_FIELD");
            }
        }
    }

    private static void validateAliasVersion(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || node.asLong() < 1) {
            throw new IllegalArgumentException("INVALID_EXPECTED_ALIAS_VERSION");
        }
    }

    private static void validateUuid(JsonNode node, String code) {
        if (node == null || !node.isString()) {
            throw new IllegalArgumentException(code);
        }
        try {
            String raw = node.asString();
            UUID parsed = UUID.fromString(raw);
            if (!parsed.toString().equalsIgnoreCase(raw)) {
                throw new IllegalArgumentException(code);
            }
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(code, e);
        }
    }
}

