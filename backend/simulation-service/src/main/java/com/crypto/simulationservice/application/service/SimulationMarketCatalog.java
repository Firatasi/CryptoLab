package com.crypto.simulationservice.application.service;

import com.crypto.financecore.finance.Decimal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
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

    public record HistoricalCandle(
            Instant openTime,
            Instant closeTime,
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal close,
            boolean closed
    ) {}

    @FunctionalInterface
    public interface HistoricalCandleProvider {
        List<HistoricalCandle> fetchCandles(
                String symbol,
                String interval,
                Instant startInclusive,
                Instant endInclusive
        );
    }

    private record CachedMarkPrice(long timestampMs, BigDecimal price) {}

    private static final int MAX_HISTORICAL_CHUNKS = 12;

    private final ObjectMapper objectMapper;
    private final String marketBaseUrl;
    private final JdbcTemplate jdbc;
    private final Map<UUID, CachedMarkPrice> livePriceCache = new ConcurrentHashMap<>();
    private volatile long catalogLoadedAtMs = 0L;
    private volatile long catalogAttemptedAtMs = 0L;
    private volatile boolean symbolTableEnsured = false;
    private volatile boolean dbSymbolsWarmed = false;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(1200))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public SimulationMarketCatalog(
            ObjectMapper objectMapper,
            String marketBaseUrl
    ) {
        this(objectMapper, marketBaseUrl, null);
    }

    @Autowired
    public SimulationMarketCatalog(
            ObjectMapper objectMapper,
            @Value("${market.base-url:http://127.0.0.1:8082}") String marketBaseUrl,
            @Nullable JdbcTemplate jdbc
    ) {
        this.objectMapper = objectMapper;
        this.marketBaseUrl = marketBaseUrl;
        this.jdbc = jdbc;
    }

    public void ensureSymbolTable() {
        if (jdbc == null || symbolTableEnsured) {
            return;
        }
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS instrument_symbol_cache (
                    instrument_id uuid PRIMARY KEY,
                    symbol text NOT NULL,
                    updated_at timestamptz NOT NULL DEFAULT now()
                )
                """);
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            symbolTableEnsured = true;
        }
    }

    private void warmupSymbolsFromDb() {
        if (jdbc == null || dbSymbolsWarmed) {
            return;
        }
        try {
            ensureSymbolTable();
            jdbc.query("SELECT instrument_id, symbol FROM instrument_symbol_cache", rs -> {
                UUID id = rs.getObject("instrument_id", UUID.class);
                String sym = rs.getString("symbol");
                if (id != null && sym != null && !sym.isBlank()) {
                    String upper = sym.trim().toUpperCase(Locale.ROOT);
                    INSTRUMENT_SYMBOLS.putIfAbsent(id, upper);
                    SYMBOL_TO_UUID.putIfAbsent(upper, id);
                }
            });
            dbSymbolsWarmed = true;
        } catch (Exception ignored) {
        }
    }

    public void rememberSymbol(UUID instrumentId, String symbol) {
        if (instrumentId == null || symbol == null || symbol.isBlank()) {
            return;
        }
        String upper = symbol.trim().toUpperCase(Locale.ROOT);
        INSTRUMENT_SYMBOLS.put(instrumentId, upper);
        SYMBOL_TO_UUID.putIfAbsent(upper, instrumentId);
        persistSymbol(instrumentId, upper);
    }

    private void persistSymbol(UUID instrumentId, String upperSymbol) {
        if (jdbc == null || instrumentId == null || upperSymbol == null || upperSymbol.isBlank()) {
            return;
        }
        try {
            ensureSymbolTable();
            jdbc.update("""
                    INSERT INTO instrument_symbol_cache(instrument_id, symbol, updated_at)
                    VALUES (?, ?, ?)
                    ON CONFLICT (instrument_id) DO UPDATE SET
                        symbol = EXCLUDED.symbol,
                        updated_at = EXCLUDED.updated_at
                    """, instrumentId, upperSymbol, Timestamp.from(Instant.now()));
        } catch (Exception ignored) {
        }
    }

    public UUID resolveInstrumentUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return SYMBOL_TO_UUID.get("BTCUSDT");
        }
        String trimmed = raw.trim();
        UUID bySymbol = SYMBOL_TO_UUID.get(trimmed.toUpperCase(Locale.ROOT));
        if (bySymbol != null) {
            return bySymbol;
        }
        try {
            return UUID.fromString(trimmed);
        } catch (IllegalArgumentException e) {
            String sym = trimmed.toUpperCase(Locale.ROOT);
            UUID derived = UUID.nameUUIDFromBytes(sym.getBytes(StandardCharsets.UTF_8));
            SYMBOL_TO_UUID.put(sym, derived);
            INSTRUMENT_SYMBOLS.put(derived, sym);
            persistSymbol(derived, sym);
            return derived;
        }
    }

    public Optional<String> resolveKnownSymbol(UUID instrumentId) {
        if (instrumentId == null) {
            return Optional.empty();
        }
        String existing = INSTRUMENT_SYMBOLS.get(instrumentId);
        if (existing != null) {
            return Optional.of(existing);
        }
        warmupSymbolsFromDb();
        existing = INSTRUMENT_SYMBOLS.get(instrumentId);
        if (existing != null) {
            return Optional.of(existing);
        }
        if (jdbc != null) {
            try {
                ensureSymbolTable();
                List<String> rows = jdbc.queryForList(
                        "SELECT symbol FROM instrument_symbol_cache WHERE instrument_id = ?",
                        String.class,
                        instrumentId
                );
                if (!rows.isEmpty() && rows.getFirst() != null && !rows.getFirst().isBlank()) {
                    String upper = rows.getFirst().trim().toUpperCase(Locale.ROOT);
                    INSTRUMENT_SYMBOLS.put(instrumentId, upper);
                    SYMBOL_TO_UUID.putIfAbsent(upper, instrumentId);
                    return Optional.of(upper);
                }
            } catch (Exception ignored) {
            }
        }
        long nowMs = System.currentTimeMillis();
        long retryCooldownMs = catalogLoadedAtMs == 0L ? 3_000L : 15_000L;
        if (nowMs - catalogLoadedAtMs >= 60_000L && nowMs - catalogAttemptedAtMs >= retryCooldownMs) {
            catalogAttemptedAtMs = nowMs;
            refreshCatalog(nowMs);
        }
        return Optional.ofNullable(INSTRUMENT_SYMBOLS.get(instrumentId));
    }

    public String resolveSymbol(UUID instrumentId) {
        return resolveKnownSymbol(instrumentId)
                .orElseGet(() -> instrumentId != null
                        ? "UNKNOWN-" + instrumentId.toString().substring(0, 8).toUpperCase(Locale.ROOT)
                        : "BTCUSDT");
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
        if (cached != null && nowMs - cached.timestampMs() < 400L) {
            return Optional.of(cached.price());
        }
        Optional<String> symbolOpt = resolveKnownSymbol(instrumentId);
        if (symbolOpt.isEmpty()) {
            return Optional.empty();
        }
        String symbol = symbolOpt.get();
        try {
            var req = HttpRequest.newBuilder()
                    .uri(URI.create(marketBaseUrl + "/api/v1/market/quotes?instrumentId=" + symbol))
                    .timeout(Duration.ofMillis(2500))
                    .GET()
                    .build();
            var resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(resp.body());
                BigDecimal bid = parsePositiveDecimal(root.path("bid").asText(""));
                BigDecimal ask = parsePositiveDecimal(root.path("ask").asText(""));
                BigDecimal midpoint = (bid != null && ask != null)
                        ? bid.add(ask).divide(BigDecimal.valueOf(2), Math.max(bid.scale(), ask.scale()) + 2, RoundingMode.HALF_UP).stripTrailingZeros()
                        : null;
                BigDecimal mark = parsePositiveDecimal(root.path("markPrice").asText(""));
                BigDecimal last = parsePositiveDecimal(root.path("lastPrice").asText(""));
                BigDecimal candidate = mark != null ? mark : last;
                if (candidate != null && bid != null && ask != null) {
                    BigDecimal lowGuard = bid.min(ask).multiply(new BigDecimal("0.999"));
                    BigDecimal highGuard = bid.max(ask).multiply(new BigDecimal("1.001"));
                    if (candidate.compareTo(lowGuard) < 0 || candidate.compareTo(highGuard) > 0) {
                        candidate = midpoint;
                    }
                }
                BigDecimal live = candidate != null ? candidate : midpoint;
                if (live != null && live.signum() > 0) {
                    livePriceCache.put(instrumentId, new CachedMarkPrice(nowMs, live));
                    return Optional.of(live);
                }
            }
        } catch (Exception ignored) {
        }
        return Optional.empty();
    }

    private static BigDecimal parsePositiveDecimal(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            BigDecimal parsed = Decimal.parse(raw.trim());
            return parsed.signum() > 0 ? parsed : null;
        } catch (RuntimeException ex) {
            return null;
        }
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

    public List<HistoricalCandle> fetchHistoricalCandles(
            String symbol,
            String preferredInterval,
            Instant startInclusive,
            Instant endInclusive
    ) {
        if (symbol == null || symbol.isBlank() || startInclusive == null || endInclusive == null
                || !endInclusive.isAfter(startInclusive)) {
            return List.of();
        }
        String upperSym = symbol.trim().toUpperCase(Locale.ROOT);
        long gapSeconds = Duration.between(startInclusive, endInclusive).getSeconds();
        String interval = (preferredInterval != null && !preferredInterval.isBlank())
                ? preferredInterval.trim()
                : resolveAdaptiveInterval(gapSeconds);
        long stepMs = intervalToMillis(interval);
        long startMs = Math.max(1L, startInclusive.toEpochMilli() - stepMs);
        long endMs = endInclusive.toEpochMilli();

        TreeMap<Instant, HistoricalCandle> ordered = new TreeMap<>();
        long cursorMs = startMs;
        for (int chunk = 0; chunk < MAX_HISTORICAL_CHUNKS && cursorMs <= endMs; chunk++) {
            List<HistoricalCandle> page = fetchHistoricalCandleChunk(upperSym, interval, cursorMs, endMs);
            if (page == null) {
                return List.of();
            }
            if (page.isEmpty()) {
                break;
            }
            long maxOpenMs = cursorMs;
            for (HistoricalCandle c : page) {
                ordered.put(c.openTime(), c);
                long oMs = c.openTime().toEpochMilli();
                if (oMs > maxOpenMs) {
                    maxOpenMs = oMs;
                }
            }
            if (page.size() < 1000 || maxOpenMs + stepMs <= cursorMs) {
                break;
            }
            cursorMs = maxOpenMs + stepMs;
        }
        return new ArrayList<>(ordered.values());
    }

    private List<HistoricalCandle> fetchHistoricalCandleChunk(
            String upperSym,
            String interval,
            long startMs,
            long endMs
    ) {
        String encodedSym = URLEncoder.encode(upperSym, StandardCharsets.UTF_8);
        // 1) Öncelik: yerel market-data-service (Binance USD-M Futures cache/REST)
        try {
            var req = HttpRequest.newBuilder()
                    .uri(URI.create(String.format(
                            Locale.ROOT,
                            "%s/api/v1/market/candles?instrumentId=%s&interval=%s&limit=1000&startTime=%d&endTime=%d",
                            marketBaseUrl, encodedSym, interval, startMs, endMs)))
                    .timeout(Duration.ofMillis(4000))
                    .GET()
                    .build();
            var resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(resp.body());
                JsonNode bars = root.path("bars");
                if (bars.isArray()) {
                    List<HistoricalCandle> out = new ArrayList<>(bars.size());
                    for (JsonNode b : bars) {
                        HistoricalCandle parsed = parseMarketBar(b, endMs);
                        if (parsed != null) {
                            out.add(parsed);
                        }
                    }
                    return out;
                }
            }
        } catch (Exception ignored) {
        }

        // 2) Fallback: market-data-service boot edilmemişse doğrudan Binance public REST klines
        for (String url : List.of(
                String.format(Locale.ROOT,
                        "https://fapi.binance.com/fapi/v1/klines?symbol=%s&interval=%s&limit=1000&startTime=%d&endTime=%d",
                        encodedSym, interval, startMs, endMs),
                String.format(Locale.ROOT,
                        "https://data-api.binance.vision/api/v3/klines?symbol=%s&interval=%s&limit=1000&startTime=%d&endTime=%d",
                        encodedSym, interval, startMs, endMs)
        )) {
            try {
                var req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofMillis(4000))
                        .GET()
                        .build();
                var resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    JsonNode klines = objectMapper.readTree(resp.body());
                    if (klines.isArray()) {
                        List<HistoricalCandle> out = new ArrayList<>(klines.size());
                        for (JsonNode k : klines) {
                            HistoricalCandle parsed = parseBinanceKline(k, endMs);
                            if (parsed != null) {
                                out.add(parsed);
                            }
                        }
                        return out;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static HistoricalCandle parseMarketBar(JsonNode b, long endMs) {
        try {
            Instant openTime = Instant.parse(b.path("openTime").asText());
            Instant closeTime = Instant.parse(b.path("closeTime").asText());
            BigDecimal open = parsePositiveDecimal(b.path("open").asText(""));
            BigDecimal high = parsePositiveDecimal(b.path("high").asText(""));
            BigDecimal low = parsePositiveDecimal(b.path("low").asText(""));
            BigDecimal close = parsePositiveDecimal(b.path("close").asText(""));
            if (open == null || high == null || low == null || close == null) {
                return null;
            }
            boolean closed = b.path("closed").asBoolean(closeTime.toEpochMilli() <= endMs);
            return new HistoricalCandle(openTime, closeTime, open, high, low, close, closed);
        } catch (Exception ex) {
            return null;
        }
    }

    private static HistoricalCandle parseBinanceKline(JsonNode k, long endMs) {
        try {
            if (!k.isArray() || k.size() < 7) {
                return null;
            }
            long openMs = k.get(0).asLong();
            long closeMs = k.get(6).asLong();
            BigDecimal open = parsePositiveDecimal(k.get(1).asText(""));
            BigDecimal high = parsePositiveDecimal(k.get(2).asText(""));
            BigDecimal low = parsePositiveDecimal(k.get(3).asText(""));
            BigDecimal close = parsePositiveDecimal(k.get(4).asText(""));
            if (open == null || high == null || low == null || close == null) {
                return null;
            }
            boolean closed = closeMs <= endMs && closeMs <= System.currentTimeMillis();
            return new HistoricalCandle(
                    Instant.ofEpochMilli(openMs),
                    Instant.ofEpochMilli(closeMs),
                    open, high, low, close, closed
            );
        } catch (Exception ex) {
            return null;
        }
    }

    private static String resolveAdaptiveInterval(long gapSeconds) {
        if (gapSeconds <= 86_400L) {
            return "1m";
        }
        if (gapSeconds <= 7L * 86_400L) {
            return "5m";
        }
        return "15m";
    }

    private static long intervalToMillis(String interval) {
        return switch (interval) {
            case "1m" -> 60_000L;
            case "5m" -> 300_000L;
            case "15m" -> 900_000L;
            case "1h" -> 3_600_000L;
            case "4h" -> 14_400_000L;
            case "1d" -> 86_400_000L;
            default -> 60_000L;
        };
    }

    private void refreshCatalog(long nowMs) {
        try {
            var req = HttpRequest.newBuilder()
                    .uri(URI.create(marketBaseUrl + "/api/v1/market/instruments?limit=1000"))
                    .timeout(Duration.ofMillis(2500))
                    .GET()
                    .build();
            var resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(resp.body());
                JsonNode items = root.path("items");
                if (items.isArray() && !items.isEmpty()) {
                    catalogLoadedAtMs = nowMs;
                    for (JsonNode item : items) {
                        String sym = item.path("symbol").asText("");
                        registerCatalogSymbol(sym, item.path("instrumentId").asText(""));
                    }
                    return;
                }
            }
        } catch (Exception ignored) {
        }

        // Yedek: market-data-service henüz hazır değilse doğrudan Binance exchangeInfo ile sembol UUID eşleşmesini yükle
        try {
            var req = HttpRequest.newBuilder()
                    .uri(URI.create("https://fapi.binance.com/fapi/v1/exchangeInfo"))
                    .timeout(Duration.ofMillis(2500))
                    .GET()
                    .build();
            var resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode symbols = objectMapper.readTree(resp.body()).path("symbols");
                if (symbols.isArray() && !symbols.isEmpty()) {
                    catalogLoadedAtMs = nowMs;
                    for (JsonNode s : symbols) {
                        if ("TRADING".equals(s.path("status").asText(""))
                                && "PERPETUAL".equals(s.path("contractType").asText(""))
                                && "USDT".equals(s.path("quoteAsset").asText(""))) {
                            registerCatalogSymbol(s.path("symbol").asText(""), "");
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void registerCatalogSymbol(String sym, String rawId) {
        if (sym == null || sym.isBlank()) {
            return;
        }
        String upper = sym.trim().toUpperCase(Locale.ROOT);
        UUID derived = UUID.nameUUIDFromBytes(upper.getBytes(StandardCharsets.UTF_8));
        boolean isNewDerived = INSTRUMENT_SYMBOLS.putIfAbsent(derived, upper) == null;
        SYMBOL_TO_UUID.putIfAbsent(upper, derived);
        if (isNewDerived) {
            persistSymbol(derived, upper);
        }
        if (rawId != null && !rawId.isBlank()) {
            try {
                UUID parsedId = UUID.fromString(rawId.trim());
                boolean isNewRaw = INSTRUMENT_SYMBOLS.putIfAbsent(parsedId, upper) == null;
                if (isNewRaw && !parsedId.equals(derived)) {
                    persistSymbol(parsedId, upper);
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
    }
}
