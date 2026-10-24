package com.crypto.strategyservice.infrastructure.validation;

import com.crypto.strategyservice.api.validation.ContractGate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.Set;
import java.util.UUID;

/**
 * Sorumluluk: TrainingJsonController üzerinden geçen JSON isteklerinin
 * alan, tip ve zorunluluk şemasını doğrular; tanımsız veya bozuk yükleri reddeder.
 */
@Component
public class JsonContractGate implements ContractGate {

    private final JsonMapper json;

    public JsonContractGate(JsonMapper json) {
        this.json = json;
    }

    @Override
    public void validate(String method, String path, byte[] body) {
        if (method == null || path == null || !path.startsWith("/") || path.contains("..")) {
            throw new IllegalArgumentException("INVALID_ROUTE");
        }
        if ("GET".equals(method)) {
            if (body != null && body.length > 0) {
                throw new IllegalArgumentException("GET_BODY_FORBIDDEN");
            }
            return;
        }
        if (body != null && body.length > 1_048_576) {
            throw new IllegalArgumentException("BODY_TOO_LARGE");
        }

        if ("POST".equals(method) && path.matches("^/runs/[0-9a-fA-F\\-]{36}/cancel$")) {
            if (body == null || body.length == 0) {
                return;
            }
            JsonNode node = parseObject(body);
            requireAllowedFields(node, Set.of(), Set.of("reason"));
            if (node.hasNonNull("reason")) {
                requireNonBlankString(node, "reason", 500);
            }
            return;
        }

        if (body == null || body.length == 0) {
            throw new IllegalArgumentException("MISSING_BODY");
        }

        JsonNode node = parseObject(body);

        if ("POST".equals(method) && "/source-imports".equals(path)) {
            requireAllowedFields(node, Set.of("sourceUrl", "title", "rightsDeclaration"), Set.of("author"));
            String url = requireNonBlankString(node, "sourceUrl", 2048);
            validateHttpsUrl(url);
            requireNonBlankString(node, "title", 200);
            requireNonBlankString(node, "rightsDeclaration", 4000);
            if (node.hasNonNull("author")) {
                requireNonBlankString(node, "author", 200);
            }
            return;
        }

        if ("DELETE".equals(method) && path.matches("^/sources/[0-9a-fA-F\\-]{36}$")) {
            requireAllowedFields(node, Set.of("expectedVersion"), Set.of());
            requireMinInt(node, "expectedVersion", 1);
            return;
        }

        if ("POST".equals(method) && "/example-jobs".equals(path)) {
            requireAllowedFields(node, Set.of("sourceId", "taskType"), Set.of());
            requireUuid(node, "sourceId");
            requireEnum(node, "taskType", Set.of("NUMERIC", "SFT", "EXPLANATION"));
            return;
        }

        if ("PATCH".equals(method) && path.matches("^/examples/[0-9a-fA-F\\-]{36}$")) {
            requireAllowedFields(node, Set.of("expectedVersion", "reviewStatus"), Set.of("notes"));
            requireMinInt(node, "expectedVersion", 1);
            requireEnum(node, "reviewStatus", Set.of("APPROVED", "REJECTED"));
            if (node.hasNonNull("notes")) {
                requireNonBlankString(node, "notes", 2000);
            }
            return;
        }

        if ("POST".equals(method) && "/datasets".equals(path)) {
            requireAllowedFields(node, Set.of("name", "taskType", "exampleIds"), Set.of());
            requireNonBlankString(node, "name", 120);
            requireEnum(node, "taskType", Set.of("NUMERIC", "SFT", "EXPLANATION"));
            JsonNode examples = node.get("exampleIds");
            if (examples == null || !examples.isArray() || examples.isEmpty()) {
                throw new IllegalArgumentException("INVALID_EXAMPLE_IDS");
            }
            for (JsonNode item : examples) {
                if (!item.isString()) {
                    throw new IllegalArgumentException("INVALID_EXAMPLE_IDS");
                }
                parseUuid(item.asString());
            }
            return;
        }

        if ("POST".equals(method) && "/runs".equals(path)) {
            requireAllowedFields(node, Set.of("taskType", "datasetId", "config", "seed"), Set.of());
            requireEnum(node, "taskType", Set.of("NUMERIC", "SFT", "EXPLANATION"));
            requireUuid(node, "datasetId");
            JsonNode config = node.get("config");
            if (config == null || !config.isObject()) {
                throw new IllegalArgumentException("INVALID_RUN_CONFIG");
            }
            JsonNode seed = node.get("seed");
            if (seed == null || !seed.isIntegralNumber()) {
                throw new IllegalArgumentException("INVALID_SEED");
            }
            return;
        }

        throw new IllegalArgumentException("UNSUPPORTED_TRAINING_CONTRACT_ROUTE");
    }

    private JsonNode parseObject(byte[] body) {
        try {
            JsonNode node = json.readTree(body);
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException("INVALID_BODY");
            }
            return node;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("MALFORMED_JSON", e);
        }
    }

    private static void requireAllowedFields(JsonNode node, Set<String> required, Set<String> optional) {
        for (String field : node.propertyNames()) {
            if (!required.contains(field) && !optional.contains(field)) {
                throw new IllegalArgumentException("UNKNOWN_FIELD");
            }
        }
        for (String req : required) {
            if (!node.hasNonNull(req)) {
                throw new IllegalArgumentException("MISSING_FIELD");
            }
        }
    }

    private static String requireNonBlankString(JsonNode node, String field, int maxLen) {
        JsonNode val = node.get(field);
        if (val == null || !val.isString()) {
            throw new IllegalArgumentException("INVALID_FIELD_" + field.toUpperCase());
        }
        String text = val.asString();
        if (text.isBlank() || text.length() > maxLen) {
            throw new IllegalArgumentException("INVALID_FIELD_" + field.toUpperCase());
        }
        return text;
    }

    private static void requireMinInt(JsonNode node, String field, long min) {
        JsonNode val = node.get(field);
        if (val == null || !val.isIntegralNumber() || val.asLong() < min) {
            throw new IllegalArgumentException("INVALID_FIELD_" + field.toUpperCase());
        }
    }

    private static void requireUuid(JsonNode node, String field) {
        String text = requireNonBlankString(node, field, 36);
        parseUuid(text);
    }

    private static void parseUuid(String text) {
        try {
            UUID parsed = UUID.fromString(text);
            if (!parsed.toString().equalsIgnoreCase(text)) {
                throw new IllegalArgumentException("INVALID_UUID");
            }
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("INVALID_UUID", e);
        }
    }

    private static void requireEnum(JsonNode node, String field, Set<String> allowed) {
        String value = requireNonBlankString(node, field, 64);
        if (!allowed.contains(value)) {
            throw new IllegalArgumentException("INVALID_ENUM_" + field.toUpperCase());
        }
    }

    private static void validateHttpsUrl(String rawUrl) {
        try {
            URI uri = URI.create(rawUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException("INVALID_SOURCE_URL");
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("INVALID_SOURCE_URL", e);
        }
    }
}

