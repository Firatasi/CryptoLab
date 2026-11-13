package com.crypto.simulationservice.application.service;

import com.crypto.financecore.finance.Decimal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SimulationMarketCatalog {

    private static final Map<UUID, String> INSTRUMENT_SYMBOLS = new ConcurrentHashMap<>(Map.of(
            UUID.fromString("11111111-1111-4111-8111-111111111111"), "BTCUSDT",
            UUID.fromString("22222222-2222-4222-8222-222222222222"), "ETHUSDT",
            UUID.fromString("33333333-3333-4333-8333-333333333333"), "SOLUSDT",
            UUID.fromString("44444444-4444-4444-8444-444444444444"), "BNBUSDT",
            UUID.fromString("55555555-5555-4555-8555-555555555555"), "XRPUSDT",
            UUID.fromString("66666666-6666-4666-8666-666666666666"), "DOGEUSDT",
            UUID.fromString("77777777-7777-4777-8777-777777777777"), "ADAUSDT",
            UUID.fromString("12121212-1212-4212-8212-121212121212"), "AVAXUSDT",
            UUID.fromString("13131313-1313-4313-8313-131313131313"), "LINKUSDT",
            UUID.fromString("14141414-1414-4414-8414-141414141414"), "SUIUSDT"
    ));

    private static final Map<String, UUID> SYMBOL_TO_UUID = new ConcurrentHashMap<>(Map.of(
            "BTCUSDT", UUID.fromString("11111111-1111-4111-8111-111111111111"),
            "ETHUSDT", UUID.fromString("22222222-2222-4222-8222-222222222222"),
            "SOLUSDT", UUID.fromString("33333333-3333-4333-8333-333333333333"),
            "BNBUSDT", UUID.fromString("44444444-4444-4444-8444-444444444444"),
            "XRPUSDT", UUID.fromString("55555555-5555-4555-8555-555555555555"),
            "DOGEUSDT", UUID.fromString("66666666-6666-4666-8666-666666666666"),
            "ADAUSDT", UUID.fromString("77777777-7777-4777-8777-777777777777"),
            "AVAXUSDT", UUID.fromString("12121212-1212-4212-8212-121212121212"),
            "LINKUSDT", UUID.fromString("13131313-1313-4313-8313-131313131313"),
            "SUIUSDT", UUID.fromString("14141414-1414-4414-8414-141414141414")
    ));

    private static final Map<UUID, BigDecimal> REFERENCE_PRICES = Map.of(
            UUID.fromString("11111111-1111-4111-8111-111111111111"), new BigDecimal("88420.00"),
            UUID.fromString("22222222-2222-4222-8222-222222222222"), new BigDecimal("2650.00"),
            UUID.fromString("33333333-3333-4333-8333-333333333333"), new BigDecimal("185.00"),
            UUID.fromString("44444444-4444-4444-8444-444444444444"), new BigDecimal("620.00"),
            UUID.fromString("55555555-5555-4555-8555-555555555555"), new BigDecimal("2.4500"),
            UUID.fromString("66666666-6666-4666-8666-666666666666"), new BigDecimal("0.3200"),
            UUID.fromString("77777777-7777-4777-8777-777777777777"), new BigDecimal("0.9500"),
            UUID.fromString("12121212-1212-4212-8212-121212121212"), new BigDecimal("34.50"),
            UUID.fromString("13131313-1313-4313-8313-131313131313"), new BigDecimal("18.20"),
            UUID.fromString("14141414-1414-4414-8414-141414141414"), new BigDecimal("3.1500")
    );

    private record CachedMarkPrice(long timestampMs, BigDecimal price) {}

    private final ObjectMapper objectMapper;
    private final String marketBaseUrl;
    private final Map<UUID, CachedMarkPrice> livePriceCache = new ConcurrentHashMap<>();
    private volatile long catalogLoadedAtMs = 0L;
    private volatile long catalogAttemptedAtMs = 0L;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(1200))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public SimulationMarketCatalog(
            ObjectMapper objectMapper,
            @Value("${market.base-url:http://127.0.0.1:8082}") String marketBaseUrl
    ) {
        this.objectMapper = objectMapper;
        this.marketBaseUrl = marketBaseUrl;
    }

    public UUID resolveInstrumentUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return SYMBOL_TO_UUID.get("BTCUSDT");
        }
        String trimmed = raw.trim();
        UUID bySymbol = SYMBOL_TO_UUID.get(trimmed.toUpperCase());
        if (bySymbol != null) {
            return bySymbol;
        }
        try {
            return UUID.fromString(trimmed);
        } catch (IllegalArgumentException e) {
            String sym = trimmed.toUpperCase();
            UUID derived = UUID.nameUUIDFromBytes(sym.getBytes(StandardCharsets.UTF_8));
            SYMBOL_TO_UUID.put(sym, derived);
            INSTRUMENT_SYMBOLS.put(derived, sym);
            return derived;
        }
    }

    public String resolveSymbol(UUID instrumentId) {
        String existing = INSTRUMENT_SYMBOLS.get(instrumentId);
        if (existing != null) {
            return existing;
        }
        long nowMs = System.currentTimeMillis();
        if (nowMs - catalogLoadedAtMs >= 60_000L && nowMs - catalogAttemptedAtMs >= 15_000L) {
            catalogAttemptedAtMs = nowMs;
            refreshCatalog(nowMs);
        }
        return INSTRUMENT_SYMBOLS.getOrDefault(instrumentId, "BTCUSDT");
    }

    public BigDecimal defaultReferencePrice(UUID instrumentId) {
        return REFERENCE_PRICES.getOrDefault(instrumentId, new BigDecimal("71480.00"));
    }

    public BigDecimal fetchLiveMarkPrice(UUID instrumentId, BigDecimal fallback) {
        return fetchLiveMarkPriceIfAvailable(instrumentId).orElse(fallback);
    }

    public Optional<BigDecimal> fetchLiveMarkPriceIfAvailable(UUID instrumentId) {
        long nowMs = System.currentTimeMillis();
        CachedMarkPrice cached = livePriceCache.get(instrumentId);
        if (cached != null && nowMs - cached.timestampMs() < 1500L) {
            return Optional.of(cached.price());
        }
        String symbol = resolveSymbol(instrumentId);
        try {
            var req = HttpRequest.newBuilder()
                    .uri(URI.create(marketBaseUrl + "/api/v1/market/quotes?instrumentId=" + symbol))
                    .timeout(Duration.ofMillis(2500))
                    .GET()
                    .build();
            var resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(resp.body());
                String markStr = root.path("markPrice").asText("");
                if (!markStr.isBlank()) {
                    BigDecimal live = Decimal.parse(markStr);
                    if (live.signum() > 0) {
                        livePriceCache.put(instrumentId, new CachedMarkPrice(nowMs, live));
                        return Optional.of(live);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return Optional.empty();
    }

    public static BigDecimal resolveTakerFeeRate(UUID ruleProfileId, String orderType) {
        boolean isLimit = "LIMIT".equalsIgnoreCase(orderType);
        String profileStr = ruleProfileId != null ? ruleProfileId.toString() : "";
        if ("88888888-8888-4888-8888-888888888889".equals(profileStr)) {
            return isLimit ? new BigDecimal("0.0001") : new BigDecimal("0.00025");
        }
        if ("88888888-8888-4888-8888-888888888890".equals(profileStr)) {
            return isLimit ? BigDecimal.ZERO : new BigDecimal("0.0003");
        }
        return isLimit ? new BigDecimal("0.0002") : new BigDecimal("0.0004");
    }

    private void refreshCatalog(long nowMs) {
        try {
            var req = HttpRequest.newBuilder()
                    .uri(URI.create(marketBaseUrl + "/api/v1/market/instruments?limit=1000"))
                    .timeout(Duration.ofMillis(2500))
                    .GET()
                    .build();
            var resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return;
            }
            JsonNode root = objectMapper.readTree(resp.body());
            JsonNode items = root.path("items");
            if (items.isArray() && !items.isEmpty()) {
                catalogLoadedAtMs = nowMs;
            }
            for (JsonNode item : items) {
                String sym = item.path("symbol").asText("");
                if (!sym.isBlank()) {
                    String upper = sym.toUpperCase();
                    UUID derived = UUID.nameUUIDFromBytes(upper.getBytes(StandardCharsets.UTF_8));
                    INSTRUMENT_SYMBOLS.putIfAbsent(derived, upper);
                    SYMBOL_TO_UUID.putIfAbsent(upper, derived);
                    String rawId = item.path("instrumentId").asText("");
                    if (!rawId.isBlank()) {
                        try {
                            INSTRUMENT_SYMBOLS.putIfAbsent(UUID.fromString(rawId), upper);
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }
}
