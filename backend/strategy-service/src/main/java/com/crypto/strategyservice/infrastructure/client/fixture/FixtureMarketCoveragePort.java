package com.crypto.strategyservice.infrastructure.client.fixture;

import com.crypto.strategyservice.application.port.MarketCoveragePort;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class FixtureMarketCoveragePort implements MarketCoveragePort {

    public record BackfillCall(UUID backfillId, UUID instrumentId, int horizonSeconds, Instant asOf) {
    }

    private static final UUID DEFAULT_DATASET_ID = UUID.fromString("77777777-7777-4777-8777-777777777777");
    private static final UUID DEFAULT_SNAPSHOT_ID = UUID.fromString("88888888-8888-4888-8888-888888888888");

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

    private final java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofMillis(1200))
            .build();
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
    private final String marketDataBaseUrl;
    private volatile long catalogLoadedAtMs = 0L;

    @org.springframework.beans.factory.annotation.Autowired
    public FixtureMarketCoveragePort() {
        this("http://127.0.0.1:8082");
    }

    FixtureMarketCoveragePort(String marketDataBaseUrl) {
        this.marketDataBaseUrl = marketDataBaseUrl;
    }

    public static void registerSymbol(UUID instrumentId, String symbol) {
        if (instrumentId != null && symbol != null && !symbol.isBlank()) {
            INSTRUMENT_SYMBOLS.put(instrumentId, symbol.trim().toUpperCase());
        }
    }

    private String resolveSymbol(UUID instrumentId) {
        if (instrumentId == null) {
            return null;
        }
        String cached = INSTRUMENT_SYMBOLS.get(instrumentId);
        if (cached != null) {
            return cached;
        }
        long nowMs = System.currentTimeMillis();
        if (nowMs - catalogLoadedAtMs < 60_000L) {
            return INSTRUMENT_SYMBOLS.get(instrumentId);
        }
        catalogLoadedAtMs = nowMs;
        try {
            var req = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(marketDataBaseUrl + "/api/v1/market/instruments?limit=1000"))
                    .timeout(java.time.Duration.ofMillis(3000))
                    .GET()
                    .build();
            var resp = httpClient.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                var items = objectMapper.readTree(resp.body()).path("items");
                if (items.isArray()) {
                    for (var item : items) {
                        String sym = item.path("symbol").asText("");
                        if (!sym.isBlank()) {
                            String upper = sym.trim().toUpperCase();
                            UUID derived = UUID.nameUUIDFromBytes(upper.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            INSTRUMENT_SYMBOLS.putIfAbsent(derived, upper);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return INSTRUMENT_SYMBOLS.get(instrumentId);
    }

    private final Map<UUID, MarketEvaluation> evaluationsByInstrument = new ConcurrentHashMap<>();
    private final List<BackfillCall> backfillCalls = new CopyOnWriteArrayList<>();

    public void givenEvaluation(UUID instrumentId, MarketEvaluation evaluation) {
        evaluationsByInstrument.put(instrumentId, evaluation);
    }

    public List<BackfillCall> backfillCalls() {
        return List.copyOf(backfillCalls);
    }

    public void clear() {
        evaluationsByInstrument.clear();
        backfillCalls.clear();
    }

    @Override
    public MarketEvaluation evaluate(UUID instrumentId, int horizonSeconds, Instant asOf) {
        MarketEvaluation custom = evaluationsByInstrument.get(instrumentId);
        if (custom != null) {
            return custom;
        }
        BigDecimal refPrice = switch (instrumentId.toString()) {
            case "11111111-1111-4111-8111-111111111111" -> new BigDecimal("88420.00");
            case "22222222-2222-4222-8222-222222222222" -> new BigDecimal("2650.00");
            case "33333333-3333-4333-8333-333333333333" -> new BigDecimal("185.00");
            case "44444444-4444-4444-8444-444444444444" -> new BigDecimal("620.00");
            case "55555555-5555-4555-8555-555555555555" -> new BigDecimal("2.45");
            case "66666666-6666-4666-8666-666666666666" -> new BigDecimal("0.32");
            case "77777777-7777-4777-8777-777777777777" -> new BigDecimal("0.95");
            case "12121212-1212-4212-8212-121212121212" -> new BigDecimal("34.50");
            case "13131313-1313-4313-8313-131313131313" -> new BigDecimal("18.20");
            case "14141414-1414-4414-8414-141414141414" -> new BigDecimal("3.15");
            default -> new BigDecimal("100");
        };
        BigDecimal atr = switch (instrumentId.toString()) {
            case "11111111-1111-4111-8111-111111111111" -> new BigDecimal("640.00");
            case "22222222-2222-4222-8222-222222222222" -> new BigDecimal("28.00");
            case "33333333-3333-4333-8333-333333333333" -> new BigDecimal("3.20");
            case "44444444-4444-4444-8444-444444444444" -> new BigDecimal("7.50");
            case "55555555-5555-4555-8555-555555555555" -> new BigDecimal("0.045");
            case "66666666-6666-4666-8666-666666666666" -> new BigDecimal("0.008");
            case "77777777-7777-4777-8777-777777777777" -> new BigDecimal("0.022");
            case "12121212-1212-4212-8212-121212121212" -> new BigDecimal("0.75");
            case "13131313-1313-4313-8313-131313131313" -> new BigDecimal("0.42");
            case "14141414-1414-4414-8414-141414141414" -> new BigDecimal("0.08");
            default -> new BigDecimal("5");
        };
        String symbol = resolveSymbol(instrumentId);
        if (symbol != null) {
            try {
                var req = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(marketDataBaseUrl + "/api/v1/market/quotes?instrumentId=" + symbol))
                        .timeout(java.time.Duration.ofMillis(2500))
                        .GET()
                        .build();
                var resp = httpClient.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    var root = objectMapper.readTree(resp.body());
                    String markStr = root.path("markPrice").asText("");
                    if (!markStr.isBlank()) {
                        BigDecimal livePrice = new BigDecimal(markStr);
                        if (livePrice.signum() > 0) {
                            BigDecimal liveAtr = fetchLiveAtr(symbol, horizonSeconds, livePrice);
                            return new MarketEvaluation(
                                    DEFAULT_DATASET_ID,
                                    DEFAULT_SNAPSHOT_ID,
                                    asOf,
                                    1.0,
                                    livePrice,
                                    liveAtr,
                                    true
                            );
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return new MarketEvaluation(
                DEFAULT_DATASET_ID,
                DEFAULT_SNAPSHOT_ID,
                asOf,
                1.0,
                refPrice,
                atr,
                true
        );
    }

    @Override
    public void triggerBackfill(UUID backfillId, UUID instrumentId, int horizonSeconds, Instant asOf) {
        backfillCalls.add(new BackfillCall(backfillId, instrumentId, horizonSeconds, asOf));
    }

    private BigDecimal fetchLiveAtr(String symbol, int horizonSeconds, BigDecimal livePrice) {
        int scale = Math.max(livePrice.scale(), 4);
        BigDecimal fallback = livePrice.multiply(new BigDecimal("0.008"))
                .setScale(scale, java.math.RoundingMode.HALF_UP);
        if (fallback.signum() <= 0) {
            fallback = new BigDecimal("0.0001");
        }
        try {
            String interval = mapHorizonToInterval(horizonSeconds);
            var req = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(marketDataBaseUrl
                            + "/api/v1/market/candles?instrumentId=" + symbol
                            + "&interval=" + interval
                            + "&limit=30"))
                    .timeout(java.time.Duration.ofMillis(2500))
                    .GET()
                    .build();
            var resp = httpClient.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return fallback;
            }
            var bars = objectMapper.readTree(resp.body()).path("bars");
            BigDecimal computed = computeAtr14(bars, scale);
            if (computed == null || computed.signum() <= 0) {
                return fallback;
            }
            // Clamp ATR so 2x stop distance stays distinct from entry and 6x target stays positive.
            BigDecimal minAtr = livePrice.multiply(new BigDecimal("0.0005"))
                    .setScale(scale, java.math.RoundingMode.HALF_UP);
            if (minAtr.signum() <= 0) {
                minAtr = new BigDecimal("0.0001");
            }
            BigDecimal maxAtr = livePrice.multiply(new BigDecimal("0.12"))
                    .setScale(scale, java.math.RoundingMode.HALF_UP);
            if (computed.compareTo(minAtr) < 0) {
                return minAtr;
            }
            if (maxAtr.signum() > 0 && computed.compareTo(maxAtr) > 0) {
                return maxAtr;
            }
            return computed;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    static String mapHorizonToInterval(int horizonSeconds) {
        if (horizonSeconds <= 120) {
            return "1m";
        }
        if (horizonSeconds <= 600) {
            return "5m";
        }
        if (horizonSeconds <= 1800) {
            return "15m";
        }
        if (horizonSeconds <= 7200) {
            return "1h";
        }
        if (horizonSeconds <= 21600) {
            return "4h";
        }
        return "1d";
    }

    static BigDecimal computeAtr14(com.fasterxml.jackson.databind.JsonNode bars, int scale) {
        if (bars == null || !bars.isArray() || bars.size() < 2) {
            return null;
        }
        java.util.List<BigDecimal> trueRanges = new java.util.ArrayList<>();
        BigDecimal prevClose = null;
        for (var bar : bars) {
            String hStr = bar.path("high").asText("");
            String lStr = bar.path("low").asText("");
            String cStr = bar.path("close").asText("");
            if (hStr.isBlank() || lStr.isBlank() || cStr.isBlank()) {
                continue;
            }
            BigDecimal high = new BigDecimal(hStr);
            BigDecimal low = new BigDecimal(lStr);
            BigDecimal close = new BigDecimal(cStr);
            if (high.compareTo(low) < 0) {
                BigDecimal tmp = high;
                high = low;
                low = tmp;
            }
            if (prevClose != null) {
                BigDecimal rangeHl = high.subtract(low);
                BigDecimal rangeHc = high.subtract(prevClose).abs();
                BigDecimal rangeLc = low.subtract(prevClose).abs();
                BigDecimal tr = rangeHl.max(rangeHc).max(rangeLc);
                trueRanges.add(tr);
            }
            prevClose = close;
        }
        if (trueRanges.isEmpty()) {
            return null;
        }
        int window = Math.min(14, trueRanges.size());
        int start = trueRanges.size() - window;
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = start; i < trueRanges.size(); i++) {
            sum = sum.add(trueRanges.get(i));
        }
        return sum.divide(BigDecimal.valueOf(window), scale, java.math.RoundingMode.HALF_UP);
    }
}

