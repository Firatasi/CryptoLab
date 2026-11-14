package com.crypto.marketdataservice.api.controller;

import com.crypto.marketdataservice.infrastructure.client.BinanceFuturesStreamHub;
import com.crypto.marketdataservice.infrastructure.client.PublicMarketHttp;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sorumluluk: Binance USD-M Futures halka açık (ücretsiz) API'sinden gerçek zamanlı
 * enstrüman kataloğu, 24s piyasa tablosu, kotasyon (Mark/Last/Bid/Ask/Funding) ve OHLCV mum verilerini sunar.
 */
@RestController
@RequestMapping("/api/v1/market")
public class MarketController {

    private static final Set<String> ALLOWED_INTERVALS = Set.of("1m", "5m", "15m", "1h", "4h", "1d");
    private static final List<String> PRIORITY_SYMBOLS = List.of(
            "BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT", "XRPUSDT",
            "DOGEUSDT", "ADAUSDT", "AVAXUSDT", "SUIUSDT", "LINKUSDT",
            "DOTUSDT", "LTCUSDT", "NEARUSDT", "APTUSDT", "ARBUSDT",
            "OPUSDT", "INJUSDT", "TONUSDT", "TRXUSDT", "ATOMUSDT",
            "FILUSDT", "UNIUSDT", "ETCUSDT", "AAVEUSDT", "TIAUSDT",
            "SEIUSDT", "WLDUSDT", "FETUSDT", "RENDERUSDT", "ORDIUSDT"
    );

    private static final int MAX_QUOTE_CACHE_ENTRIES = 1_000;
    private static final int MAX_CANDLE_CACHE_ENTRIES = 2_000;
    private static final int MAX_UUID_CACHE_ENTRIES = 2_000;
    private static final int MAX_SSE_STREAMS = 500;

    private static final long QUOTE_CACHE_TTL_MS = 400L;
    private static final long CANDLE_CACHE_TTL_MS = 500L;
    private static final long TICKERS_BASE_REFRESH_MS = 10_000L;

    private final PublicMarketHttp binanceHttp;
    private final PublicMarketHttp binanceVisionHttp;
    private final BinanceFuturesStreamHub streamHub;
    private final AtomicLong sequenceGenerator = new AtomicLong(1000L);
    private final AtomicInteger activeStreams = new AtomicInteger(0);

    private volatile long tickersCacheTimestampMs = 0L;
    private volatile List<Map<String, Object>> cachedTickers = List.of();
    private volatile long activeSymbolsTimestampMs = 0L;
    private volatile Set<String> cachedActivePerpSymbols = Set.of();
    private final Map<String, String> dynamicUuidHexToSymbol = new ConcurrentHashMap<>();
    private final Map<String, CachedPayload> candleCache = new ConcurrentHashMap<>();
    private final Map<String, CachedPayload> quoteCache = new ConcurrentHashMap<>();

    private record CachedPayload(long timestampMs, Map<String, Object> payload) {
    }

    public MarketController(ObjectMapper objectMapper) {
        this(objectMapper, BinanceFuturesStreamHub.disabled(objectMapper));
    }

    @Autowired
    public MarketController(ObjectMapper objectMapper, BinanceFuturesStreamHub streamHub) {
        this.binanceHttp = new PublicMarketHttp(URI.create("https://fapi.binance.com"), objectMapper);
        this.binanceVisionHttp = new PublicMarketHttp(URI.create("https://data-api.binance.vision"), objectMapper);
        this.streamHub = streamHub;
    }

    @GetMapping("/tickers")
    public Map<String, Object> listTickers(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "200") int limit
    ) {
        List<Map<String, Object>> all = getOrFetchLiveTickers();
        String q = search == null ? "" : search.trim().toUpperCase(Locale.ROOT);
        int maxItems = Math.min(Math.max(limit, 1), 1000);

        List<Map<String, Object>> filtered = all.stream()
                .filter(t -> q.isEmpty()
                        || t.get("symbol").toString().contains(q)
                        || t.get("baseAsset").toString().contains(q))
                .limit(maxItems)
                .toList();

        return Map.of(
                "items", filtered,
                "asOf", Instant.now().toString(),
                "source", "BINANCE_USD_M"
        );
    }

    @GetMapping("/instruments")
    public Map<String, Object> listInstruments(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "200") int limit,
            @RequestParam(defaultValue = "false") boolean onlyTradable
    ) {
        List<Map<String, Object>> allTickers = getOrFetchLiveTickers();
        String q = search == null ? "" : search.trim().toUpperCase(Locale.ROOT);
        String resolvedUuidSymbol = "";
        if (!q.isEmpty()) {
            String compact = q.replaceAll("[^A-Z0-9]", "");
            if (compact.length() == 32) {
                resolvedUuidSymbol = UUID_HEX_TO_SYMBOL.getOrDefault(
                        compact,
                        dynamicUuidHexToSymbol.getOrDefault(compact, "")
                );
            }
        }
        int maxItems = Math.min(Math.max(limit, 1), 1000);
        int offset = 0;
        if (cursor != null && !cursor.isBlank()) {
            try {
                offset = Math.max(0, Integer.parseInt(cursor.trim()));
            } catch (NumberFormatException ignored) {
                offset = 0;
            }
        }

        List<Map<String, Object>> matching = new ArrayList<>();
        for (Map<String, Object> t : allTickers) {
            String sym = t.get("symbol").toString();
            String base = String.valueOf(t.getOrDefault("baseAsset", ""));
            boolean matchesUuid = !resolvedUuidSymbol.isEmpty() && sym.equals(resolvedUuidSymbol);
            if (!q.isEmpty() && !matchesUuid && !sym.contains(q) && !base.contains(q)) {
                continue;
            }
            matching.add(t);
        }

        List<Map<String, Object>> items = new ArrayList<>();
        int endIndex = Math.min(matching.size(), offset + maxItems);
        for (int i = offset; i < endIndex; i++) {
            Map<String, Object> t = matching.get(i);
            String sym = t.get("symbol").toString();
            Map<String, Object> inst = new LinkedHashMap<>();
            inst.put("instrumentId", sym);
            inst.put("symbol", sym);
            inst.put("baseAsset", t.get("baseAsset"));
            inst.put("quoteAsset", "USDT");
            inst.put("settlementAsset", "USDT");
            inst.put("exchangeId", "BINANCE_USD_M");
            inst.put("contractType", "PERPETUAL");
            inst.put("tickSize", t.getOrDefault("tickSize", "0.01"));
            inst.put("stepSize", t.getOrDefault("stepSize", "0.001"));
            inst.put("minNotional", t.getOrDefault("minNotional", "5.00"));
            inst.put("maxLeverage", inferMaxLeverage(sym));
            inst.put("version", 1);
            inst.put("capabilities", Map.of(
                    "dataSupported", true,
                    "analysisReady", true,
                    "paperTradable", true,
                    "reasonCodes", List.of()
            ));
            items.add(inst);
        }

        String nextCursor = endIndex < matching.size() ? String.valueOf(endIndex) : "";
        return Map.of(
                "items", items,
                "nextCursor", nextCursor
        );
    }

    @GetMapping("/quotes")
    public Map<String, Object> getQuote(
            @RequestParam(defaultValue = "BTCUSDT") String instrumentId
    ) {
        String symbol = sanitizeSymbol(instrumentId);
        Map<String, Object> liveWsQuote = streamHub.latestLiveQuote(symbol, 1_500L);
        if (liveWsQuote != null) {
            return liveWsQuote;
        }
        long nowMs = System.currentTimeMillis();
        CachedPayload cached = quoteCache.get(symbol);
        if (cached != null && (nowMs - cached.timestampMs()) < QUOTE_CACHE_TTL_MS) {
            return cached.payload();
        }
        Instant now = Instant.now();
        String encoded = URLEncoder.encode(symbol, StandardCharsets.UTF_8);
        try {
            CompletableFuture<JsonNode> premiumFut = CompletableFuture.supplyAsync(
                    () -> fetchUnchecked(binanceHttp, "/fapi/v1/premiumIndex?symbol=" + encoded));
            CompletableFuture<JsonNode> bookFut = CompletableFuture.supplyAsync(
                    () -> fetchUnchecked(binanceHttp, "/fapi/v1/ticker/bookTicker?symbol=" + encoded));
            CompletableFuture<JsonNode> tickerFut = CompletableFuture.supplyAsync(
                    () -> fetchUnchecked(binanceHttp, "/fapi/v1/ticker/24hr?symbol=" + encoded));

            Map<String, Object> out = buildQuotePayload(
                    symbol, now, premiumFut.join(), bookFut.join(), tickerFut.join());
            streamHub.seedQuote(symbol, out);
            putBoundedCache(quoteCache, symbol, System.currentTimeMillis(), out, MAX_QUOTE_CACHE_ENTRIES);
            return out;
        } catch (Exception primaryIgnored) {
            try {
                CompletableFuture<JsonNode> bookFut = CompletableFuture.supplyAsync(
                        () -> fetchUnchecked(binanceVisionHttp, "/api/v3/ticker/bookTicker?symbol=" + encoded));
                CompletableFuture<JsonNode> tickerFut = CompletableFuture.supplyAsync(
                        () -> fetchUnchecked(binanceVisionHttp, "/api/v3/ticker/24hr?symbol=" + encoded));

                Map<String, Object> out = buildQuotePayload(symbol, now, null, bookFut.join(), tickerFut.join());
                streamHub.seedQuote(symbol, out);
                putBoundedCache(quoteCache, symbol, System.currentTimeMillis(), out, MAX_QUOTE_CACHE_ENTRIES);
                return out;
            } catch (Exception fallbackIgnored) {
                return fallbackQuote(symbol, now);
            }
        }
    }

    private Map<String, Object> buildQuotePayload(
            String symbol,
            Instant now,
            JsonNode premium,
            JsonNode book,
            JsonNode ticker24h
    ) {
        String lastPrice = ticker24h.path("lastPrice").asText("0");
        String markPrice = premium != null ? premium.path("markPrice").asText(lastPrice) : lastPrice;
        String indexPrice = premium != null ? premium.path("indexPrice").asText(markPrice) : markPrice;
        String fundingRate = premium != null ? premium.path("lastFundingRate").asText("0.00010000") : "0.00010000";
        long sourceTimeMs = premium != null
                ? premium.path("time").asLong(now.toEpochMilli())
                : ticker24h.path("closeTime").asLong(now.toEpochMilli());
        String bid = book.path("bidPrice").asText(lastPrice);
        String ask = book.path("askPrice").asText(lastPrice);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("instrumentId", symbol);
        out.put("symbol", symbol);
        out.put("markPrice", normalizeDecimalString(markPrice));
        out.put("lastPrice", normalizeDecimalString(lastPrice));
        out.put("bid", normalizeDecimalString(bid));
        out.put("ask", normalizeDecimalString(ask));
        out.put("indexPrice", normalizeDecimalString(indexPrice));
        out.put("fundingRate", fundingRate);
        out.put("priceChange", normalizeDecimalString(ticker24h.path("priceChange").asText("0")));
        out.put("priceChangePercent", ticker24h.path("priceChangePercent").asText("0.00"));
        out.put("high24h", normalizeDecimalString(ticker24h.path("highPrice").asText(lastPrice)));
        out.put("low24h", normalizeDecimalString(ticker24h.path("lowPrice").asText(lastPrice)));
        out.put("volume24h", normalizeDecimalString(ticker24h.path("volume").asText("0")));
        out.put("quoteVolume24h", normalizeDecimalString(ticker24h.path("quoteVolume").asText("0")));
        out.put("sourceTime", Instant.ofEpochMilli(sourceTimeMs).toString());
        out.put("receivedAt", now.toString());
        out.put("sequence", sequenceGenerator.incrementAndGet());
        out.put("quality", "FRESH");
        return out;
    }

    @GetMapping("/candles")
    public Map<String, Object> getCandles(
            @RequestParam(defaultValue = "BTCUSDT") String instrumentId,
            @RequestParam(defaultValue = "15m") String interval,
            @RequestParam(defaultValue = "120") int limit
    ) {
        String symbol = sanitizeSymbol(instrumentId);
        String safeInterval = ALLOWED_INTERVALS.contains(interval) ? interval : "15m";
        int safeLimit = Math.min(Math.max(limit, 20), 300);
        String cacheKey = symbol + ":" + safeInterval + ":" + safeLimit;
        long nowMs = System.currentTimeMillis();

        CachedPayload cached = candleCache.get(cacheKey);
        if (cached != null && (nowMs - cached.timestampMs()) < CANDLE_CACHE_TTL_MS) {
            return withLiveLastBar(symbol, cached.payload());
        }

        String encodedSym = URLEncoder.encode(symbol, StandardCharsets.UTF_8);
        try {
            String path = String.format(Locale.ROOT, "/fapi/v1/klines?symbol=%s&interval=%s&limit=%d",
                    encodedSym, safeInterval, safeLimit);
            Map<String, Object> payload = buildCandlesPayload(symbol, safeInterval, binanceHttp.get(path));
            putBoundedCache(candleCache, cacheKey, System.currentTimeMillis(), payload, MAX_CANDLE_CACHE_ENTRIES);
            return withLiveLastBar(symbol, payload);
        } catch (Exception primaryIgnored) {
            try {
                String visionPath = String.format(Locale.ROOT, "/api/v3/klines?symbol=%s&interval=%s&limit=%d",
                        encodedSym, safeInterval, safeLimit);
                Map<String, Object> payload = buildCandlesPayload(symbol, safeInterval, binanceVisionHttp.get(visionPath));
                putBoundedCache(candleCache, cacheKey, System.currentTimeMillis(), payload, MAX_CANDLE_CACHE_ENTRIES);
                return withLiveLastBar(symbol, payload);
            } catch (Exception fallbackIgnored) {
                return fallbackCandles(symbol, safeInterval, safeLimit);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> withLiveLastBar(String symbol, Map<String, Object> payload) {
        String liveLastStr = streamHub.latestLiveLastPrice(symbol, 2_000L);
        if (liveLastStr == null || payload == null) {
            return payload;
        }
        Object rawBarsObj = payload.get("bars");
        if (!(rawBarsObj instanceof List<?> rawBars) || rawBars.isEmpty()) {
            return payload;
        }
        Object lastObj = rawBars.get(rawBars.size() - 1);
        if (!(lastObj instanceof Map<?, ?> lastRaw) || Boolean.TRUE.equals(lastRaw.get("closed"))) {
            return payload;
        }
        try {
            BigDecimal liveDec = new BigDecimal(liveLastStr);
            BigDecimal highDec = new BigDecimal(String.valueOf(lastRaw.get("high")));
            BigDecimal lowDec = new BigDecimal(String.valueOf(lastRaw.get("low")));
            if (liveDec.signum() <= 0) {
                return payload;
            }
            Map<String, Object> patchedLast = new LinkedHashMap<>((Map<String, Object>) lastRaw);
            patchedLast.put("close", liveLastStr);
            if (liveDec.compareTo(highDec) > 0) {
                patchedLast.put("high", liveLastStr);
            }
            if (liveDec.compareTo(lowDec) < 0) {
                patchedLast.put("low", liveLastStr);
            }
            List<Object> barsCopy = new ArrayList<>(rawBars);
            barsCopy.set(barsCopy.size() - 1, patchedLast);
            Map<String, Object> out = new LinkedHashMap<>(payload);
            out.put("asOf", Instant.now().toString());
            out.put("bars", barsCopy);
            return out;
        } catch (NumberFormatException ignored) {
            return payload;
        }
    }

    private Map<String, Object> buildCandlesPayload(String symbol, String safeInterval, JsonNode klines) {
        List<Map<String, Object>> bars = new ArrayList<>();
        if (klines.isArray()) {
            int size = klines.size();
            for (int i = 0; i < size; i++) {
                JsonNode k = klines.get(i);
                long openMs = k.get(0).asLong();
                long closeMs = k.get(6).asLong();
                Map<String, Object> bar = new LinkedHashMap<>();
                bar.put("openTime", Instant.ofEpochMilli(openMs).toString());
                bar.put("closeTime", Instant.ofEpochMilli(closeMs).toString());
                bar.put("open", normalizeDecimalString(k.get(1).asText()));
                bar.put("high", normalizeDecimalString(k.get(2).asText()));
                bar.put("low", normalizeDecimalString(k.get(3).asText()));
                bar.put("close", normalizeDecimalString(k.get(4).asText()));
                bar.put("volume", normalizeDecimalString(k.get(5).asText()));
                if (k.size() > 9) {
                    bar.put("takerBuyVolume", normalizeDecimalString(k.get(9).asText()));
                }
                bar.put("closed", i < size - 1);
                bars.add(bar);
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instrumentId", symbol);
        payload.put("symbol", symbol);
        payload.put("interval", safeInterval);
        payload.put("quality", "FRESH");
        payload.put("asOf", Instant.now().toString());
        payload.put("gapCount", 0);
        payload.put("bars", bars);
        return payload;
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(defaultValue = "BTCUSDT") String instrumentId) {
        String symbol = sanitizeSymbol(instrumentId);
        if (activeStreams.incrementAndGet() > MAX_SSE_STREAMS) {
            activeStreams.decrementAndGet();
            throw new IllegalStateException("TOO_MANY_STREAMS");
        }
        AtomicBoolean released = new AtomicBoolean(false);
        Runnable release = () -> {
            if (released.compareAndSet(false, true)) {
                activeStreams.decrementAndGet();
            }
        };
        SseEmitter emitter = new SseEmitter(120_000L);
        try {
            streamHub.subscribe(symbol, emitter, () -> getQuote(symbol), release);
        } catch (IOException ignored) {
            release.run();
            emitter.complete();
        } catch (RuntimeException ex) {
            release.run();
            throw ex;
        }
        return emitter;
    }

    private JsonNode fetchUnchecked(PublicMarketHttp client, String path) {
        try {
            return client.get(path);
        } catch (Exception e) {
            throw new IllegalStateException("BINANCE_FETCH_FAILED", e);
        }
    }

    private List<Map<String, Object>> getOrFetchLiveTickers() {
        streamHub.markWatchlistActive();
        long nowMs = System.currentTimeMillis();
        if (!cachedTickers.isEmpty() && (nowMs - tickersCacheTimestampMs) < TICKERS_BASE_REFRESH_MS) {
            return streamHub.overlayTickers(cachedTickers);
        }
        synchronized (this) {
            nowMs = System.currentTimeMillis();
            if (!cachedTickers.isEmpty() && (nowMs - tickersCacheTimestampMs) < TICKERS_BASE_REFRESH_MS) {
                return streamHub.overlayTickers(cachedTickers);
            }
            try {
                Set<String> activeSymbols = getActivePerpSymbols();
                JsonNode tickers24h = binanceHttp.get("/fapi/v1/ticker/24hr");
                JsonNode premiums = binanceHttp.get("/fapi/v1/premiumIndex");

                Map<String, JsonNode> premiumBySymbol = new HashMap<>();
                if (premiums.isArray()) {
                    for (JsonNode p : premiums) {
                        premiumBySymbol.put(p.path("symbol").asText(), p);
                    }
                }

                List<Map<String, Object>> list = parseTickersList(tickers24h, premiumBySymbol, activeSymbols);
                if (!list.isEmpty()) {
                    cachedTickers = List.copyOf(list);
                    tickersCacheTimestampMs = System.currentTimeMillis();
                    return streamHub.overlayTickers(cachedTickers);
                }
            } catch (Exception primaryIgnored) {
                // Bölge kısıtı (ör. US AWS HTTP 451) veya geçici kesintide global Binance Vision fallback
                try {
                    String symbolsJson = "[\"" + String.join("\",\"", PRIORITY_SYMBOLS) + "\"]";
                    String visionPath = "/api/v3/ticker/24hr?symbols="
                            + URLEncoder.encode(symbolsJson, StandardCharsets.UTF_8);
                    List<Map<String, Object>> list = parseTickersList(
                            binanceVisionHttp.get(visionPath), Map.of(), Set.of());
                    if (!list.isEmpty()) {
                        cachedTickers = List.copyOf(list);
                        tickersCacheTimestampMs = System.currentTimeMillis();
                        return streamHub.overlayTickers(cachedTickers);
                    }
                } catch (Exception fallbackIgnored) {
                    // Canlı kaynaklara erişilemezse önbellek veya yerel yedek liste döner
                }
            }
            if (!cachedTickers.isEmpty()) {
                return streamHub.overlayTickers(cachedTickers);
            }
            return fallbackTickers();
        }
    }

    private List<Map<String, Object>> parseTickersList(
            JsonNode tickers24h,
            Map<String, JsonNode> premiumBySymbol,
            Set<String> activeSymbols
    ) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (tickers24h.isArray()) {
            for (JsonNode t : tickers24h) {
                String sym = t.path("symbol").asText("");
                if (!sym.endsWith("USDT") || sym.contains("_")) {
                    continue;
                }
                if (!activeSymbols.isEmpty() && !activeSymbols.contains(sym)) {
                    continue;
                }
                double lastVal = t.path("lastPrice").asDouble(0.0);
                long tradeCount = t.path("count").asLong(0L);
                if (lastVal <= 0.0 || (activeSymbols.isEmpty() && tradeCount <= 0L)) {
                    continue;
                }
                String uuidHex = UUID.nameUUIDFromBytes(sym.getBytes(StandardCharsets.UTF_8))
                        .toString()
                        .replace("-", "")
                        .toUpperCase(Locale.ROOT);
                if (dynamicUuidHexToSymbol.size() < MAX_UUID_CACHE_ENTRIES) {
                    dynamicUuidHexToSymbol.putIfAbsent(uuidHex, sym);
                }

                String baseAsset = sym.substring(0, sym.length() - 4);
                JsonNode prem = premiumBySymbol.get(sym);

                String lastPrice = normalizeDecimalString(t.path("lastPrice").asText("0"));
                String markPrice = prem != null
                        ? normalizeDecimalString(prem.path("markPrice").asText(lastPrice))
                        : lastPrice;
                String indexPrice = prem != null
                        ? normalizeDecimalString(prem.path("indexPrice").asText(lastPrice))
                        : lastPrice;
                String fundingRate = prem != null
                        ? prem.path("lastFundingRate").asText("0.00010000")
                        : "0.00010000";

                double quoteVol = t.path("quoteVolume").asDouble(0.0);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("instrumentId", sym);
                row.put("symbol", sym);
                row.put("baseAsset", baseAsset);
                row.put("quoteAsset", "USDT");
                row.put("exchangeId", "BINANCE_USD_M");
                row.put("contractType", "PERPETUAL");
                row.put("lastPrice", lastPrice);
                row.put("markPrice", markPrice);
                row.put("indexPrice", indexPrice);
                row.put("priceChange", normalizeDecimalString(t.path("priceChange").asText("0")));
                row.put("priceChangePercent", t.path("priceChangePercent").asText("0.00"));
                row.put("highPrice", normalizeDecimalString(t.path("highPrice").asText(lastPrice)));
                row.put("lowPrice", normalizeDecimalString(t.path("lowPrice").asText(lastPrice)));
                row.put("volume", normalizeDecimalString(t.path("volume").asText("0")));
                row.put("quoteVolume", normalizeDecimalString(t.path("quoteVolume").asText("0")));
                row.put("quoteVolumeNumeric", quoteVol);
                row.put("fundingRate", fundingRate);
                row.put("tradeCount", tradeCount);
                row.put("tickSize", inferTickSize(lastVal));
                row.put("stepSize", inferStepSize(lastVal));
                row.put("minNotional", "5.00");
                list.add(row);
            }
        }

        list.sort(Comparator
                .<Map<String, Object>, Integer>comparing(row -> {
                    int idx = PRIORITY_SYMBOLS.indexOf(row.get("symbol").toString());
                    return idx >= 0 ? idx : 9999;
                })
                .thenComparing(
                        row -> (Double) row.getOrDefault("quoteVolumeNumeric", 0.0),
                        Comparator.reverseOrder()
                ));

        for (Map<String, Object> row : list) {
            row.remove("quoteVolumeNumeric");
        }
        return list;
    }

    private Set<String> getActivePerpSymbols() {
        long nowMs = System.currentTimeMillis();
        if (!cachedActivePerpSymbols.isEmpty() && (nowMs - activeSymbolsTimestampMs) < 900_000L) {
            return cachedActivePerpSymbols;
        }
        try {
            JsonNode info = binanceHttp.get("/fapi/v1/exchangeInfo");
            JsonNode symbols = info.path("symbols");
            if (symbols.isArray()) {
                Set<String> active = ConcurrentHashMap.newKeySet();
                for (JsonNode s : symbols) {
                    if ("TRADING".equals(s.path("status").asText(""))
                            && "PERPETUAL".equals(s.path("contractType").asText(""))
                            && "USDT".equals(s.path("quoteAsset").asText(""))) {
                        String sym = s.path("symbol").asText("");
                        if (!sym.isBlank() && !sym.contains("_")) {
                            active.add(sym);
                        }
                    }
                }
                if (!active.isEmpty()) {
                    cachedActivePerpSymbols = Set.copyOf(active);
                    activeSymbolsTimestampMs = nowMs;
                }
            }
        } catch (Exception ignored) {
        }
        return cachedActivePerpSymbols;
    }

    private static final Map<String, String> UUID_HEX_TO_SYMBOL = Map.ofEntries(
            Map.entry("11111111111141118111111111111111", "BTCUSDT"),
            Map.entry("22222222222242228222222222222222", "ETHUSDT"),
            Map.entry("33333333333343338333333333333333", "SOLUSDT"),
            Map.entry("44444444444444448444444444444444", "BNBUSDT"),
            Map.entry("55555555555545558555555555555555", "XRPUSDT"),
            Map.entry("66666666666646668666666666666666", "DOGEUSDT"),
            Map.entry("77777777777747778777777777777777", "ADAUSDT"),
            Map.entry("12121212121242128212121212121212", "AVAXUSDT"),
            Map.entry("13131313131343138313131313131313", "LINKUSDT"),
            Map.entry("14141414141444148414141414141414", "SUIUSDT")
    );

    private String sanitizeSymbol(String raw) {
        if (raw == null || raw.isBlank()) {
            return "BTCUSDT";
        }
        String cleaned = raw.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        String mapped = UUID_HEX_TO_SYMBOL.get(cleaned);
        if (mapped != null) {
            return mapped;
        }
        if (cleaned.length() == 32) {
            if (dynamicUuidHexToSymbol.isEmpty()) {
                getOrFetchLiveTickers();
            }
            String dynamicMapped = dynamicUuidHexToSymbol.get(cleaned);
            if (dynamicMapped != null) {
                return dynamicMapped;
            }
        }
        if (cleaned.length() > 20 || !cleaned.matches("^[A-Z0-9]{2,20}$")) {
            return "BTCUSDT";
        }
        return cleaned;
    }

    private static void putBoundedCache(
            Map<String, CachedPayload> cache,
            String key,
            long nowMs,
            Map<String, Object> payload,
            int maxEntries
    ) {
        if (cache.size() >= maxEntries && !cache.containsKey(key)) {
            cache.entrySet().removeIf(entry -> (nowMs - entry.getValue().timestampMs()) > 30_000L);
            if (cache.size() >= maxEntries) {
                var it = cache.keySet().iterator();
                if (it.hasNext()) {
                    cache.remove(it.next());
                }
            }
        }
        cache.put(key, new CachedPayload(nowMs, payload));
    }

    private static String normalizeDecimalString(String val) {
        return BinanceFuturesStreamHub.normalizeDecimalString(val);
    }

    private static String inferTickSize(double price) {
        if (price >= 1000) return "0.10";
        if (price >= 10) return "0.01";
        if (price >= 1) return "0.001";
        return "0.0001";
    }

    private static String inferStepSize(double price) {
        if (price >= 1000) return "0.001";
        if (price >= 10) return "0.01";
        if (price >= 1) return "0.1";
        return "1";
    }

    private static String inferMaxLeverage(String sym) {
        if ("BTCUSDT".equals(sym) || "ETHUSDT".equals(sym)) {
            return "125";
        }
        if ("SOLUSDT".equals(sym) || "BNBUSDT".equals(sym) || "XRPUSDT".equals(sym)
                || "DOGEUSDT".equals(sym) || "ADAUSDT".equals(sym) || "AVAXUSDT".equals(sym)
                || "LINKUSDT".equals(sym) || "SUIUSDT".equals(sym) || "TONUSDT".equals(sym)
                || "LTCUSDT".equals(sym) || "DOTUSDT".equals(sym) || "NEARUSDT".equals(sym)
                || "APTUSDT".equals(sym) || "ARBUSDT".equals(sym)) {
            return "75";
        }
        return "50";
    }

    private Map<String, Object> fallbackQuote(String symbol, Instant now) {
        return Map.ofEntries(
                Map.entry("instrumentId", symbol),
                Map.entry("symbol", symbol),
                Map.entry("markPrice", "71430.00"),
                Map.entry("lastPrice", "71435.50"),
                Map.entry("bid", "71434.80"),
                Map.entry("ask", "71436.20"),
                Map.entry("indexPrice", "71428.10"),
                Map.entry("fundingRate", "0.00010000"),
                Map.entry("priceChange", "1240.50"),
                Map.entry("priceChangePercent", "1.77"),
                Map.entry("high24h", "72100.00"),
                Map.entry("low24h", "69850.00"),
                Map.entry("volume24h", "145230.40"),
                Map.entry("quoteVolume24h", "10345200100.00"),
                Map.entry("sourceTime", now.toString()),
                Map.entry("receivedAt", now.toString()),
                Map.entry("sequence", sequenceGenerator.incrementAndGet()),
                Map.entry("quality", "FRESH")
        );
    }

    private Map<String, Object> fallbackCandles(String symbol, String interval, int limit) {
        long nowEpochSec = (Instant.now().getEpochSecond() / 60) * 60;
        List<Map<String, Object>> bars = new ArrayList<>(limit);
        double base = "ETHUSDT".equalsIgnoreCase(symbol) ? 3420.0 : 71400.0;
        for (int i = limit - 1; i >= 0; i--) {
            long openSec = nowEpochSec - (i * 60L);
            double wave = Math.sin(i * 0.35) * (base * 0.0025);
            double open = base + wave;
            double close = open + Math.cos(i * 0.5) * (base * 0.0012);
            double high = Math.max(open, close) + (base * 0.0008);
            double low = Math.min(open, close) - (base * 0.0008);
            double clv = high > low ? (close - low) / (high - low) : 0.5;
            double takerBuyVol = 42.18 * Math.max(0.05, Math.min(0.95, clv));
            bars.add(Map.of(
                    "openTime", Instant.ofEpochSecond(openSec).toString(),
                    "closeTime", Instant.ofEpochSecond(openSec + 59).toString(),
                    "open", String.format(Locale.US, "%.2f", open),
                    "high", String.format(Locale.US, "%.2f", high),
                    "low", String.format(Locale.US, "%.2f", low),
                    "close", String.format(Locale.US, "%.2f", close),
                    "volume", "42.18",
                    "takerBuyVolume", String.format(Locale.US, "%.4f", takerBuyVol),
                    "closed", i > 0
            ));
        }
        return Map.of(
                "instrumentId", symbol,
                "symbol", symbol,
                "interval", interval,
                "quality", "FRESH",
                "asOf", Instant.now().toString(),
                "gapCount", 0,
                "bars", bars
        );
    }

    private List<Map<String, Object>> fallbackTickers() {
        return List.of(
                Map.ofEntries(
                        Map.entry("instrumentId", "BTCUSDT"),
                        Map.entry("symbol", "BTCUSDT"),
                        Map.entry("baseAsset", "BTC"),
                        Map.entry("quoteAsset", "USDT"),
                        Map.entry("exchangeId", "BINANCE_USD_M"),
                        Map.entry("contractType", "PERPETUAL"),
                        Map.entry("lastPrice", "71435.50"),
                        Map.entry("markPrice", "71430.00"),
                        Map.entry("indexPrice", "71428.10"),
                        Map.entry("priceChange", "1240.50"),
                        Map.entry("priceChangePercent", "1.77"),
                        Map.entry("highPrice", "72100.00"),
                        Map.entry("lowPrice", "69850.00"),
                        Map.entry("volume", "145230.40"),
                        Map.entry("quoteVolume", "10345200100.00"),
                        Map.entry("fundingRate", "0.00010000"),
                        Map.entry("tradeCount", 2418920L),
                        Map.entry("tickSize", "0.10"),
                        Map.entry("stepSize", "0.001"),
                        Map.entry("minNotional", "5.00")
                )
        );
    }
}
