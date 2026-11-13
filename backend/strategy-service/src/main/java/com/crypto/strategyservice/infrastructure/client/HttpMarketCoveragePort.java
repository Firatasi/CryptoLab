package com.crypto.strategyservice.infrastructure.client;

import com.crypto.strategyservice.application.port.MarketCoveragePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live market coverage & ATR reader querying market-data-service:8082 so
 * Strategy evaluates reference price, horizon-matched ATR(14), candle coverage,
 * and instrument exchange leverage limits from live Binance Futures markets.
 */
@Component
@Primary
@Profile("!fixture & !test")
public class HttpMarketCoveragePort implements MarketCoveragePort {

    private static final Logger log = LoggerFactory.getLogger(HttpMarketCoveragePort.class);

    private final URI baseUri;
    private final HttpClient httpClient;
    private final JsonMapper json;
    private final Clock clock;

    private final Map<UUID, Integer> instrumentMaxLeverage = new ConcurrentHashMap<>();
    private final Map<UUID, String> uuidToSymbol = new ConcurrentHashMap<>();

    public HttpMarketCoveragePort(
            @Value("${market.data.url:http://127.0.0.1:8082}") String baseUrl,
            JsonMapper json,
            Clock clock
    ) {
        this.baseUri = URI.create(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        this.json = json;
        this.clock = clock;
        seedCanonicalSymbols();
    }

    private void seedCanonicalSymbols() {
        Map<String, String> known = Map.of(
                "11111111-1111-4111-8111-111111111111", "BTCUSDT",
                "22222222-2222-4222-8222-222222222222", "ETHUSDT",
                "33333333-3333-4333-8333-333333333333", "SOLUSDT",
                "44444444-4444-4444-8444-444444444444", "BNBUSDT",
                "55555555-5555-4555-8555-555555555555", "XRPUSDT",
                "66666666-6666-4666-8666-666666666666", "DOGEUSDT",
                "77777777-7777-4777-8777-777777777777", "ADAUSDT",
                "12121212-1212-4212-8212-121212121212", "AVAXUSDT",
                "13131313-1313-4313-8313-131313131313", "LINKUSDT",
                "14141414-1414-4414-8414-141414141414", "SUIUSDT"
        );
        known.forEach((k, v) -> uuidToSymbol.put(UUID.fromString(k), v));
    }

    @Override
    public MarketEvaluation evaluate(UUID instrumentId, int horizonSeconds, Instant asOf) {
        refreshCatalogIfNeeded(instrumentId);
        String symbolOrId = uuidToSymbol.getOrDefault(instrumentId, instrumentId.toString());
        String interval = horizonToInterval(horizonSeconds);
        Instant effectiveAsOf = asOf != null ? asOf : clock.instant();

        BigDecimal refPrice = fetchQuotePrice(symbolOrId);
        List<BigDecimal[]> bars = fetchClosedOhlc(symbolOrId, interval, 120);

        if (refPrice == null && !bars.isEmpty()) {
            refPrice = bars.get(bars.size() - 1)[2];
        }

        if (refPrice == null || refPrice.compareTo(BigDecimal.ZERO) <= 0 || bars.size() < 20) {
            return new MarketEvaluation(
                    stableUuid("dataset:" + symbolOrId + ":" + interval),
                    stableUuid("snapshot:" + symbolOrId + ":" + effectiveAsOf),
                    effectiveAsOf,
                    Math.min(0.89, bars.size() / 60.0),
                    refPrice != null ? refPrice : BigDecimal.ONE,
                    BigDecimal.ZERO,
                    false
            );
        }

        BigDecimal atr = computeSmaAtr(bars, 14);
        double coverage = Math.min(1.0, bars.size() / 60.0);
        boolean sufficient = coverage >= 0.90 && atr.compareTo(BigDecimal.ZERO) > 0;

        return new MarketEvaluation(
                stableUuid("dataset:" + symbolOrId + ":" + interval + ":" + bars.size()),
                stableUuid("snapshot:" + symbolOrId + ":" + interval + ":" + effectiveAsOf.getEpochSecond()),
                effectiveAsOf,
                coverage,
                refPrice,
                atr,
                sufficient
        );
    }

    @Override
    public int maxLeverageForInstrument(UUID instrumentId) {
        refreshCatalogIfNeeded(instrumentId);
        return instrumentMaxLeverage.getOrDefault(instrumentId, 50);
    }

    @Override
    public void triggerBackfill(UUID backfillId, UUID instrumentId, int horizonSeconds, Instant asOf) {
        refreshCatalogIfNeeded(instrumentId);
        String symbolOrId = uuidToSymbol.getOrDefault(instrumentId, instrumentId.toString());
        fetchClosedOhlc(symbolOrId, horizonToInterval(horizonSeconds), 120);
    }

    private void refreshCatalogIfNeeded(UUID targetId) {
        if (instrumentMaxLeverage.containsKey(targetId)) {
            return;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(baseUri.resolve("/api/v1/market/instruments?limit=150"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return;
            }
            JsonNode items = json.readTree(resp.body()).path("items");
            if (items.isArray()) {
                for (JsonNode item : items) {
                    String rawId = item.path("instrumentId").asText(null);
                    String sym = item.path("symbol").asText(null);
                    int maxLev = item.path("maxLeverage").asInt(50);
                    if (rawId != null && !rawId.isBlank() && sym != null && !sym.isBlank()) {
                        UUID id = UUID.fromString(rawId.trim());
                        uuidToSymbol.put(id, sym.trim().toUpperCase());
                        instrumentMaxLeverage.put(id, Math.max(1, maxLev));
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("Failed to refresh market instruments catalog: {}", ex.getMessage());
        }
    }

    private BigDecimal fetchQuotePrice(String symbolOrId) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(baseUri.resolve("/api/v1/market/quotes?instrumentId=" + symbolOrId))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode root = json.readTree(resp.body());
                String mark = root.path("markPrice").asText("");
                String last = root.path("lastPrice").asText("");
                String picked = !mark.isBlank() ? mark : last;
                if (!picked.isBlank()) {
                    return new BigDecimal(picked);
                }
            }
        } catch (Exception ex) {
            log.warn("Quote lookup failed for {}: {}", symbolOrId, ex.getMessage());
        }
        return null;
    }

    private List<BigDecimal[]> fetchClosedOhlc(String symbolOrId, String interval, int limit) {
        List<BigDecimal[]> rows = new ArrayList<>();
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(baseUri.resolve("/api/v1/market/candles?instrumentId=" + symbolOrId
                            + "&interval=" + interval + "&limit=" + limit))
                    .timeout(Duration.ofSeconds(4))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return rows;
            }
            JsonNode bars = json.readTree(resp.body()).path("bars");
            if (bars.isArray()) {
                for (JsonNode b : bars) {
                    if (!b.path("closed").asBoolean(true)) {
                        continue;
                    }
                    BigDecimal hi = new BigDecimal(b.path("high").asText("0"));
                    BigDecimal lo = new BigDecimal(b.path("low").asText("0"));
                    BigDecimal cl = new BigDecimal(b.path("close").asText("0"));
                    if (hi.compareTo(BigDecimal.ZERO) > 0 && lo.compareTo(BigDecimal.ZERO) > 0 && cl.compareTo(BigDecimal.ZERO) > 0) {
                        rows.add(new BigDecimal[]{hi, lo, cl});
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("Candle lookup failed for {} {}: {}", symbolOrId, interval, ex.getMessage());
        }
        return rows;
    }

    private static BigDecimal computeSmaAtr(List<BigDecimal[]> bars, int period) {
        if (bars.size() <= period) {
            return BigDecimal.ZERO;
        }
        BigDecimal sum = BigDecimal.ZERO;
        int start = bars.size() - period;
        for (int i = start; i < bars.size(); i++) {
            BigDecimal hi = bars.get(i)[0];
            BigDecimal lo = bars.get(i)[1];
            BigDecimal prevClose = bars.get(i - 1)[2];
            BigDecimal tr1 = hi.subtract(lo);
            BigDecimal tr2 = hi.subtract(prevClose).abs();
            BigDecimal tr3 = lo.subtract(prevClose).abs();
            BigDecimal tr = tr1.max(tr2).max(tr3);
            sum = sum.add(tr);
        }
        return sum.divide(BigDecimal.valueOf(period), 8, RoundingMode.HALF_UP);
    }

    private static String horizonToInterval(int horizonSeconds) {
        return switch (horizonSeconds) {
            case 60 -> "1m";
            case 900 -> "15m";
            case 3600 -> "1h";
            case 86400 -> "4h";
            case 604800 -> "1d";
            default -> "1h";
        };
    }

    private static UUID stableUuid(String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }
}
