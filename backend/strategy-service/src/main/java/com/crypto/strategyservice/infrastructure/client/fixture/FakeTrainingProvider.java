package com.crypto.strategyservice.infrastructure.client.fixture;

import com.crypto.strategyservice.application.port.TrainingProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sorumluluk: Gerçek A06–A09 AI eğitim servisi hazır olana kadar B10 eğitim facade'ının
 * sözleşme, idempotency, owner izolasyonu, alias version CAS ve hata dayanıklılığı
 * testlerini çalıştıran açık etiketli fixture adaptörüdür.
 * Gerçek PDF ayrıştırma veya NUMERIC/SFT model eğitimi yaptığını iddia etmez.
 */
@Component
@Profile("fixture")
public class FakeTrainingProvider implements TrainingProvider {

    public record AuditEntry(UUID actorId, String action, UUID modelId, long aliasVersion, String detail) {
    }

    private record IdempotencyKey(UUID actorId, String method, String path, String key) {
    }

    private record StoredReply(String requestHash, Reply reply) {
    }

    private record SourceRecord(UUID sourceId, UUID ownerId, String title, String author, String rights, int version, boolean deleted) {
    }

    private record RunRecord(UUID runId, UUID ownerId, String status, String stage) {
    }

    private final JsonMapper json = JsonMapper.builder().build();
    private final Map<IdempotencyKey, StoredReply> idempotencyStore = new ConcurrentHashMap<>();
    private final Map<UUID, SourceRecord> sources = new ConcurrentHashMap<>();
    private final Map<UUID, RunRecord> runs = new ConcurrentHashMap<>();
    private final List<AuditEntry> audits = new CopyOnWriteArrayList<>();
    private final AtomicLong activeAliasVersion = new AtomicLong(1L);
    private volatile UUID activePromotedModelId = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private volatile boolean available = true;

    public void setAvailable(boolean available) {
        this.available = available;
    }

    public long activeAliasVersion() {
        return activeAliasVersion.get();
    }

    public UUID activePromotedModelId() {
        return activePromotedModelId;
    }

    public List<AuditEntry> audits() {
        return List.copyOf(audits);
    }

    public void reset() {
        idempotencyStore.clear();
        sources.clear();
        runs.clear();
        audits.clear();
        activeAliasVersion.set(1L);
        activePromotedModelId = UUID.fromString("44444444-4444-4444-8444-444444444444");
        available = true;
    }

    @Override
    public Reply upload(
            Actor actor,
            String idempotencyKey,
            String title,
            String author,
            String rights,
            String filename,
            long length,
            InputStream content
    ) {
        ensureAvailable();
        validateIdempotencyKey(idempotencyKey);
        if (!"source.pdf".equals(filename)) {
            throw new IllegalArgumentException("UNSAFE_FILENAME");
        }
        if (length <= 0 || length > 20L * 1024 * 1024) {
            throw new IllegalArgumentException("UPLOAD_SIZE");
        }
        byte[] contentBytes;
        try {
            contentBytes = content.readNBytes((int) Math.min(length + 1, 20L * 1024 * 1024 + 1));
        } catch (IOException e) {
            throw new IllegalStateException("TRAINING_UNAVAILABLE", e);
        }
        if (contentBytes.length != length) {
            throw new IllegalArgumentException("UPLOAD_LENGTH_MISMATCH");
        }

        String reqHash = sha256(title + ":" + (author == null ? "" : author) + ":" + rights + ":" + sha256Bytes(contentBytes));
        IdempotencyKey idemKey = new IdempotencyKey(actor.id(), "POST", "/sources", idempotencyKey);

        StoredReply existing = idempotencyStore.get(idemKey);
        if (existing != null) {
            if (!existing.requestHash().equals(reqHash)) {
                return conflict("IDEMPOTENCY_CONFLICT");
            }
            return existing.reply();
        }

        UUID sourceId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        sources.put(sourceId, new SourceRecord(sourceId, actor.id(), title, author, rights, 1, false));

        Reply reply = jsonReply(202, Map.of(
                "jobId", jobId.toString(),
                "sourceId", sourceId.toString(),
                "status", "QUEUED",
                "stage", "INGESTING",
                "providerMode", "FIXTURE_TRAINING_PROVIDER"
        ));
        idempotencyStore.put(idemKey, new StoredReply(reqHash, reply));
        return reply;
    }

    @Override
    public Reply json(Actor actor, String method, String relativePath, String idempotencyKey, byte[] body) {
        ensureAvailable();
        if (relativePath == null || !relativePath.startsWith("/") || relativePath.contains("..") || relativePath.contains("://")) {
            throw new IllegalArgumentException("INVALID_INTERNAL_PATH");
        }

        String cleanPath = relativePath.contains("?")
                ? relativePath.substring(0, relativePath.indexOf('?'))
                : relativePath;

        if (!"GET".equals(method)) {
            validateIdempotencyKey(idempotencyKey);
            String reqHash = sha256(method + ":" + relativePath + ":" + (body == null ? "" : sha256Bytes(body)));
            IdempotencyKey idemKey = new IdempotencyKey(actor.id(), method, cleanPath, idempotencyKey);
            StoredReply existing = idempotencyStore.get(idemKey);
            if (existing != null) {
                if (!existing.requestHash().equals(reqHash)) {
                    return conflict("IDEMPOTENCY_CONFLICT");
                }
                return existing.reply();
            }
            Reply computed = handleMutating(actor, method, cleanPath, body);
            if (computed.status() < 400) {
                idempotencyStore.put(idemKey, new StoredReply(reqHash, computed));
            }
            return computed;
        }

        return handleQuery(actor, cleanPath);
    }

    private Reply handleMutating(Actor actor, String method, String cleanPath, byte[] body) {
        JsonNode node = (body == null || body.length == 0) ? null : json.readTree(body);

        if ("POST".equals(method) && "/source-imports".equals(cleanPath)) {
            UUID sourceId = UUID.randomUUID();
            UUID jobId = UUID.randomUUID();
            String title = node.get("title").asString();
            String author = node.hasNonNull("author") ? node.get("author").asString() : null;
            String rights = node.get("rightsDeclaration").asString();
            sources.put(sourceId, new SourceRecord(sourceId, actor.id(), title, author, rights, 1, false));
            return jsonReply(202, Map.of(
                    "jobId", jobId.toString(),
                    "sourceId", sourceId.toString(),
                    "status", "QUEUED",
                    "stage", "INGESTING",
                    "providerMode", "FIXTURE_TRAINING_PROVIDER"
            ));
        }

        if ("DELETE".equals(method) && cleanPath.startsWith("/sources/")) {
            UUID sourceId = UUID.fromString(cleanPath.substring("/sources/".length()));
            SourceRecord record = sources.get(sourceId);
            if (record == null || !record.ownerId().equals(actor.id()) || record.deleted()) {
                return jsonReply(404, Map.of("code", "NOT_FOUND"));
            }
            int expectedVersion = node.get("expectedVersion").asInt();
            if (expectedVersion != record.version()) {
                return conflict("VERSION_CONFLICT");
            }
            sources.put(sourceId, new SourceRecord(
                    record.sourceId(),
                    record.ownerId(),
                    record.title(),
                    record.author(),
                    record.rights(),
                    record.version() + 1,
                    true
            ));
            return jsonReply(202, Map.of(
                    "jobId", UUID.randomUUID().toString(),
                    "sourceId", sourceId.toString(),
                    "status", "QUEUED",
                    "stage", "DELETING"
            ));
        }

        if ("POST".equals(method) && "/example-jobs".equals(cleanPath)) {
            UUID sourceId = UUID.fromString(node.get("sourceId").asString());
            SourceRecord source = sources.get(sourceId);
            if (source == null || !source.ownerId().equals(actor.id()) || source.deleted()) {
                return jsonReply(404, Map.of("code", "NOT_FOUND"));
            }
            return jsonReply(202, Map.of(
                    "jobId", UUID.randomUUID().toString(),
                    "sourceId", sourceId.toString(),
                    "status", "QUEUED",
                    "stage", "EXTRACTING",
                    "providerMode", "FIXTURE_TRAINING_PROVIDER"
            ));
        }

        if ("PATCH".equals(method) && cleanPath.startsWith("/examples/")) {
            UUID exampleId = UUID.fromString(cleanPath.substring("/examples/".length()));
            return jsonReply(200, Map.of(
                    "exampleId", exampleId.toString(),
                    "reviewStatus", node.get("reviewStatus").asString(),
                    "version", node.get("expectedVersion").asInt() + 1
            ));
        }

        if ("POST".equals(method) && "/datasets".equals(cleanPath)) {
            UUID datasetId = UUID.randomUUID();
            return jsonReply(201, Map.of(
                    "datasetId", datasetId.toString(),
                    "name", node.get("name").asString(),
                    "taskType", node.get("taskType").asString(),
                    "immutable", true,
                    "manifestHash", sha256(datasetId.toString())
            ));
        }

        if ("POST".equals(method) && "/runs".equals(cleanPath)) {
            UUID runId = UUID.randomUUID();
            runs.put(runId, new RunRecord(runId, actor.id(), "QUEUED", "QUEUED"));
            return jsonReply(202, Map.of(
                    "runId", runId.toString(),
                    "status", "QUEUED",
                    "stage", "QUEUED",
                    "providerMode", "FIXTURE_TRAINING_PROVIDER"
            ));
        }

        if ("POST".equals(method) && cleanPath.matches("^/runs/[0-9a-fA-F\\-]{36}/cancel$")) {
            String idPart = cleanPath.substring("/runs/".length(), cleanPath.length() - "/cancel".length());
            UUID runId = UUID.fromString(idPart);
            RunRecord run = runs.get(runId);
            if (run != null && !run.ownerId().equals(actor.id())) {
                return jsonReply(404, Map.of("code", "NOT_FOUND"));
            }
            runs.put(runId, new RunRecord(runId, actor.id(), "CANCELLED", "CANCELLED"));
            return jsonReply(202, Map.of(
                    "runId", runId.toString(),
                    "status", "CANCELLED",
                    "stage", "CANCELLED"
            ));
        }

        if ("POST".equals(method) && cleanPath.matches("^/models/[0-9a-fA-F\\-]{36}/promote$")) {
            if (!actor.authorities().contains("MODEL_PUBLISH")) {
                return jsonReply(403, Map.of("code", "FORBIDDEN"));
            }
            String idPart = cleanPath.substring("/models/".length(), cleanPath.length() - "/promote".length());
            UUID modelId = UUID.fromString(idPart);
            long expectedVersion = node.get("expectedAliasVersion").asLong();
            String evaluationId = node.get("evaluationId").asString();

            if (!activeAliasVersion.compareAndSet(expectedVersion, expectedVersion + 1)) {
                return conflict("ALIAS_VERSION_CONFLICT");
            }
            activePromotedModelId = modelId;
            audits.add(new AuditEntry(actor.id(), "PROMOTE", modelId, expectedVersion + 1, "eval:" + evaluationId));
            return jsonReply(200, Map.of(
                    "modelId", modelId.toString(),
                    "aliasVersion", expectedVersion + 1,
                    "evaluationId", evaluationId,
                    "status", "PROMOTED_FIXTURE"
            ));
        }

        if ("POST".equals(method) && cleanPath.matches("^/models/[0-9a-fA-F\\-]{36}/rollback$")) {
            if (!actor.authorities().contains("MODEL_PUBLISH")) {
                return jsonReply(403, Map.of("code", "FORBIDDEN"));
            }
            String idPart = cleanPath.substring("/models/".length(), cleanPath.length() - "/rollback".length());
            UUID modelId = UUID.fromString(idPart);
            long expectedVersion = node.get("expectedAliasVersion").asLong();
            String reason = node.get("reason").asString();

            if (!activeAliasVersion.compareAndSet(expectedVersion, expectedVersion + 1)) {
                return conflict("ALIAS_VERSION_CONFLICT");
            }
            activePromotedModelId = modelId;
            audits.add(new AuditEntry(actor.id(), "ROLLBACK", modelId, expectedVersion + 1, reason));
            return jsonReply(200, Map.of(
                    "modelId", modelId.toString(),
                    "aliasVersion", expectedVersion + 1,
                    "reason", reason,
                    "status", "ROLLED_BACK_FIXTURE"
            ));
        }

        return jsonReply(404, Map.of("code", "NOT_FOUND"));
    }

    private Reply handleQuery(Actor actor, String cleanPath) {
        if ("/sources".equals(cleanPath)) {
            List<Map<String, Object>> owned = new ArrayList<>();
            for (SourceRecord s : sources.values()) {
                if (s.ownerId().equals(actor.id()) && !s.deleted()) {
                    owned.add(Map.of(
                            "sourceId", s.sourceId().toString(),
                            "title", s.title(),
                            "version", s.version()
                    ));
                }
            }
            return jsonReply(200, Map.of("items", owned));
        }

        if (cleanPath.matches("^/sources/[0-9a-fA-F\\-]{36}$")) {
            UUID sourceId = UUID.fromString(cleanPath.substring("/sources/".length()));
            SourceRecord s = sources.get(sourceId);
            if (s == null || !s.ownerId().equals(actor.id()) || s.deleted()) {
                return jsonReply(404, Map.of("code", "NOT_FOUND"));
            }
            return jsonReply(200, Map.of(
                    "sourceId", s.sourceId().toString(),
                    "title", s.title(),
                    "version", s.version(),
                    "providerMode", "FIXTURE_TRAINING_PROVIDER"
            ));
        }

        if (cleanPath.matches("^/sources/[0-9a-fA-F\\-]{36}/pages/[0-9]+$")) {
            String[] parts = cleanPath.split("/");
            UUID sourceId = UUID.fromString(parts[2]);
            int page = Integer.parseInt(parts[4]);
            SourceRecord s = sources.get(sourceId);
            if (s == null || !s.ownerId().equals(actor.id()) || s.deleted()) {
                return jsonReply(404, Map.of("code", "NOT_FOUND"));
            }
            return jsonReply(200, Map.of(
                    "sourceId", sourceId.toString(),
                    "page", page,
                    "previewText", "FIXTURE_PAGE_PREVIEW"
            ));
        }

        if ("/examples".equals(cleanPath)) {
            return jsonReply(200, Map.of("items", List.of()));
        }

        if (cleanPath.matches("^/runs/[0-9a-fA-F\\-]{36}$")) {
            UUID runId = UUID.fromString(cleanPath.substring("/runs/".length()));
            RunRecord run = runs.get(runId);
            if (run == null || !run.ownerId().equals(actor.id())) {
                return jsonReply(404, Map.of("code", "NOT_FOUND"));
            }
            return jsonReply(200, Map.of(
                    "runId", run.runId().toString(),
                    "status", run.status(),
                    "stage", run.stage(),
                    "authority", "AI_TRAINING_SERVICE_FIXTURE"
            ));
        }

        if ("/models".equals(cleanPath)) {
            return jsonReply(200, Map.of(
                    "activeModelId", activePromotedModelId.toString(),
                    "aliasVersion", activeAliasVersion.get(),
                    "items", List.of()
            ));
        }

        if (cleanPath.matches("^/models/[0-9a-fA-F\\-]{36}$")) {
            UUID modelId = UUID.fromString(cleanPath.substring("/models/".length()));
            return jsonReply(200, Map.of(
                    "modelId", modelId.toString(),
                    "releaseStatus", "DEVELOPMENT_CANDIDATE",
                    "aliasVersion", activeAliasVersion.get()
            ));
        }

        return jsonReply(404, Map.of("code", "NOT_FOUND"));
    }

    private void ensureAvailable() {
        if (!available) {
            throw new IllegalStateException("TRAINING_UNAVAILABLE");
        }
    }

    private static void validateIdempotencyKey(String key) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new IllegalArgumentException("INVALID_KEY");
        }
    }

    private Reply conflict(String code) {
        return jsonReply(409, new LinkedHashMap<>(Map.of(
                "code", code,
                "retryable", false
        )));
    }

    private Reply jsonReply(int status, Map<String, Object> payload) {
        return new Reply(status, "application/json", json.writeValueAsBytes(payload));
    }

    private static String sha256(String text) {
        return sha256Bytes(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Bytes(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(md.digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("HASH_ERROR", e);
        }
    }
}

