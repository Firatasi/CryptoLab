package com.crypto.marketdataservice.infrastructure.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Binance USD-M Futures WebSocket akışından gerçek zamanlı kotasyon ve watchlist verisi toplar,
 * SSE istemcilerine düşük gecikmeli (250ms coalesce) ve güvenli geri düşüşlü canlı tick iletir.
 */
@Component
public class BinanceFuturesStreamHub {

    private static final Logger log = LoggerFactory.getLogger(BinanceFuturesStreamHub.class);
    private static final String BINANCE_WS_HOST = "fstream.binance.com";
    private static final int MAX_WS_MESSAGE_CHARS = 2 * 1024 * 1024;
    private static final int MAX_TRACKED_SYMBOLS = 2_000;
    private static final int MAX_SYMBOL_SOCKETS = 20;
    private static final long TICKER_IDLE_CLOSE_MS = 90_000L;
    private static final long FALLBACK_PUSH_INTERVAL_MS = 1_000L;
    private static final long HEARTBEAT_INTERVAL_MS = 15_000L;

    private final ObjectMapper objectMapper;
    private final boolean wsEnabled;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;
    private final AtomicLong globalSequence = new AtomicLong(10_000L);

    private final ConcurrentHashMap<String, LiveSymbolState> liveStates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, WsSessionHandle> symbolSockets = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<SseSubscription> subscriptions = new CopyOnWriteArrayList<>();

    private volatile WsSessionHandle marketSocket;
    private volatile long lastWatchlistAccessMs = 0L;

    @Autowired
    public BinanceFuturesStreamHub(
            ObjectMapper objectMapper,
            @Value("${market.live-ws.enabled:true}") boolean wsEnabled
    ) {
        this.objectMapper = objectMapper;
        this.wsEnabled = wsEnabled;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(4))
                .build();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "binance-live-stream-hub");
            t.setDaemon(true);
            return t;
        });
        this.scheduler.scheduleWithFixedDelay(this::flushLoopSafely, 250L, 250L, TimeUnit.MILLISECONDS);
    }

    public static BinanceFuturesStreamHub disabled(ObjectMapper objectMapper) {
        return new BinanceFuturesStreamHub(objectMapper, false);
    }

    public void markWatchlistActive() {
        lastWatchlistAccessMs = System.currentTimeMillis();
        ensureMarketSocket();
    }

    public void seedQuote(String symbol, Map<String, Object> payload) {
        if (symbol == null || payload == null) {
            return;
        }
        LiveSymbolState state = getOrCreateState(symbol);
        if (state == null) {
            return;
        }
        state.applySeed(payload);
    }

    public Map<String, Object> latestLiveQuote(String symbol, long maxAgeMs) {
        LiveSymbolState state = liveStates.get(symbol);
        if (state == null) {
            return null;
        }
        return state.toQuotePayloadIfFresh(maxAgeMs, globalSequence);
    }

    public String latestLiveLastPrice(String symbol, long maxAgeMs) {
        LiveSymbolState state = liveStates.get(symbol);
        if (state == null) {
            return null;
        }
        long nowMs = System.currentTimeMillis();
        if (nowMs - state.receivedAtMs > maxAgeMs) {
            return null;
        }
        return state.lastPrice;
    }

    public List<Map<String, Object>> overlayTickers(List<Map<String, Object>> baseList) {
        if (baseList == null || baseList.isEmpty() || liveStates.isEmpty()) {
            return baseList;
        }
        long nowMs = System.currentTimeMillis();
        List<Map<String, Object>> updated = new ArrayList<>(baseList.size());
        for (Map<String, Object> row : baseList) {
            String sym = String.valueOf(row.getOrDefault("symbol", ""));
            LiveSymbolState state = liveStates.get(sym);
            if (state == null || (nowMs - state.tickerUpdatedAtMs > 20_000L && nowMs - state.receivedAtMs > 20_000L)) {
                updated.add(row);
                continue;
            }
            Map<String, Object> copy = new LinkedHashMap<>(row);
            if (state.lastPrice != null) copy.put("lastPrice", state.lastPrice);
            if (state.markPrice != null) copy.put("markPrice", state.markPrice);
            if (state.indexPrice != null) copy.put("indexPrice", state.indexPrice);
            if (state.fundingRate != null) copy.put("fundingRate", state.fundingRate);
            if (state.priceChange != null) copy.put("priceChange", state.priceChange);
            if (state.priceChangePercent != null) copy.put("priceChangePercent", state.priceChangePercent);
            if (state.high24h != null) copy.put("highPrice", state.high24h);
            if (state.low24h != null) copy.put("lowPrice", state.low24h);
            if (state.volume24h != null) copy.put("volume", state.volume24h);
            if (state.quoteVolume24h != null) copy.put("quoteVolume", state.quoteVolume24h);
            if (state.tradeCount > 0L) copy.put("tradeCount", state.tradeCount);
            updated.add(copy);
        }
        return updated;
    }

    /**
     * İstemci SSE akışına abone olduğunda ilk anlık kotasyonu hemen iletir ve sonraki tickleri akışa bağlar.
     */
    public void subscribe(
            String symbol,
            SseEmitter emitter,
            Supplier<Map<String, Object>> fallbackSupplier,
            Runnable onRelease
    ) throws IOException {
        ensureMarketSocket();
        ensureSymbolSocket(symbol);

        SseSubscription sub = new SseSubscription(symbol, emitter, fallbackSupplier, onRelease);
        emitter.onCompletion(() -> closeSubscription(sub));
        emitter.onTimeout(() -> closeSubscription(sub));
        emitter.onError(ex -> closeSubscription(sub));

        Map<String, Object> initialQuote = latestLiveQuote(symbol, 1_500L);
        if (initialQuote == null) {
            initialQuote = fallbackSupplier.get();
        }
        LiveSymbolState state = liveStates.get(symbol);
        long versionAtSend = state != null ? state.version.get() : 0L;

        try {
            sub.sendTick(initialQuote, versionAtSend);
            subscriptions.add(sub);
        } catch (IOException | RuntimeException ex) {
            closeSubscription(sub);
            throw ex;
        }
    }

    private void flushLoopSafely() {
        try {
            flushSubscriptions();
            cleanupIdleSockets();
        } catch (Exception ex) {
            log.debug("Live stream flush cycle skipped: {}", ex.getMessage());
        }
    }

    void flushSubscriptions() {
        if (subscriptions.isEmpty()) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        Map<String, Map<String, Object>> fallbackBatch = new LinkedHashMap<>();

        for (SseSubscription sub : subscriptions) {
            if (sub.closed.get()) {
                subscriptions.remove(sub);
                continue;
            }
            LiveSymbolState state = liveStates.get(sub.symbol);
            long currentVersion = state != null ? state.version.get() : 0L;

            if (state != null && currentVersion > sub.lastSentVersion) {
                Map<String, Object> livePayload = state.toQuotePayloadIfFresh(3_000L, globalSequence);
                if (livePayload != null) {
                    sendSafely(sub, livePayload, currentVersion, nowMs);
                    continue;
                }
            }

            if (nowMs - sub.lastSentAtMs >= FALLBACK_PUSH_INTERVAL_MS) {
                Map<String, Object> polled = fallbackBatch.computeIfAbsent(sub.symbol, k -> {
                    try {
                        return sub.fallbackSupplier.get();
                    } catch (Exception ex) {
                        return null;
                    }
                });
                if (polled != null) {
                    sendSafely(sub, polled, currentVersion, nowMs);
                } else if (nowMs - sub.lastHeartbeatAtMs >= HEARTBEAT_INTERVAL_MS) {
                    sendHeartbeatSafely(sub, nowMs);
                }
            }
        }
    }

    private void sendSafely(SseSubscription sub, Map<String, Object> payload, long version, long nowMs) {
        try {
            sub.sendTick(payload, version);
            sub.lastSentAtMs = nowMs;
            sub.lastHeartbeatAtMs = nowMs;
        } catch (IOException | IllegalStateException ex) {
            closeSubscription(sub);
        }
    }

    private void sendHeartbeatSafely(SseSubscription sub, long nowMs) {
        try {
            sub.sendHeartbeat();
            sub.lastHeartbeatAtMs = nowMs;
        } catch (IOException | IllegalStateException ex) {
            closeSubscription(sub);
        }
    }

    private void closeSubscription(SseSubscription sub) {
        if (sub.closed.compareAndSet(false, true)) {
            subscriptions.remove(sub);
            try {
                sub.emitter.complete();
            } catch (Exception ignored) {
            }
            try {
                sub.onRelease.run();
            } catch (Exception ignored) {
            }
        }
    }

    private void ensureMarketSocket() {
        if (!wsEnabled) {
            return;
        }
        WsSessionHandle existing = marketSocket;
        if (existing != null && existing.isActiveOrCoolingDown()) {
            return;
        }
        synchronized (this) {
            if (marketSocket != null && marketSocket.isActiveOrCoolingDown()) {
                return;
            }
            long backoffMs = marketSocket != null ? marketSocket.nextBackoffMs() : 0L;
            if (marketSocket != null && System.currentTimeMillis() < marketSocket.nextRetryAtMs) {
                return;
            }
            URI uri = URI.create("wss://" + BINANCE_WS_HOST + "/stream?streams=!ticker@arr/!markPrice@arr@1s");
            WsSessionHandle handle = new WsSessionHandle("!market", uri, backoffMs);
            marketSocket = handle;
            connectWebSocket(handle, this::handleMarketStreamFrame);
        }
    }

    private void ensureSymbolSocket(String symbol) {
        if (!wsEnabled || symbol == null || !symbol.matches("^[A-Z0-9]{2,20}$")) {
            return;
        }
        WsSessionHandle existing = symbolSockets.get(symbol);
        if (existing != null && existing.isActiveOrCoolingDown()) {
            return;
        }
        if (!symbolSockets.containsKey(symbol) && symbolSockets.size() >= MAX_SYMBOL_SOCKETS) {
            return;
        }
        String streamSym = symbol.toLowerCase(Locale.ROOT);
        URI uri = URI.create(
                "wss://" + BINANCE_WS_HOST + "/stream?streams="
                        + streamSym + "@aggTrade/"
                        + streamSym + "@bookTicker/"
                        + streamSym + "@markPrice@1s"
        );
        long prevBackoff = existing != null ? existing.nextBackoffMs() : 0L;
        WsSessionHandle handle = new WsSessionHandle(symbol, uri, prevBackoff);
        symbolSockets.put(symbol, handle);
        connectWebSocket(handle, text -> handleSymbolStreamFrame(symbol, text));
    }

    private void connectWebSocket(WsSessionHandle handle, java.util.function.Consumer<String> messageConsumer) {
        handle.connecting = true;
        try {
            httpClient.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(4))
                    .buildAsync(handle.uri, new WebSocket.Listener() {
                        private final StringBuilder buffer = new StringBuilder(1024);

                        @Override
                        public void onOpen(WebSocket webSocket) {
                            handle.webSocket = webSocket;
                            handle.connecting = false;
                            handle.backoffMs = 0L;
                            webSocket.request(1);
                        }

                        @Override
                        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                            if (buffer.length() + data.length() <= MAX_WS_MESSAGE_CHARS) {
                                buffer.append(data);
                            } else {
                                buffer.setLength(0);
                            }
                            if (last) {
                                String payload = buffer.toString();
                                buffer.setLength(0);
                                if (!payload.isEmpty()) {
                                    try {
                                        messageConsumer.accept(payload);
                                    } catch (Exception ignored) {
                                    }
                                }
                            }
                            webSocket.request(1);
                            return null;
                        }

                        @Override
                        public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
                            webSocket.sendPong(message);
                            webSocket.request(1);
                            return null;
                        }

                        @Override
                        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                            handle.markDisconnected();
                            return CompletableFuture.completedFuture(null);
                        }

                        @Override
                        public void onError(WebSocket webSocket, Throwable error) {
                            handle.markDisconnected();
                            log.debug("Binance WS stream={} disconnected: {}", handle.key, error.getMessage());
                        }
                    })
                    .whenComplete((ws, err) -> {
                        if (err != null) {
                            handle.markDisconnected();
                        }
                    });
        } catch (Exception ex) {
            handle.markDisconnected();
        }
    }

    private void cleanupIdleSockets() {
        long nowMs = System.currentTimeMillis();
        Set<String> activeSymbols = ConcurrentHashMap.newKeySet();
        for (SseSubscription sub : subscriptions) {
            if (!sub.closed.get()) {
                activeSymbols.add(sub.symbol);
            }
        }

        for (Map.Entry<String, WsSessionHandle> entry : symbolSockets.entrySet()) {
            if (!activeSymbols.contains(entry.getKey())) {
                WsSessionHandle removed = symbolSockets.remove(entry.getKey());
                if (removed != null) {
                    removed.closeQuietly();
                }
            } else if (wsEnabled) {
                WsSessionHandle h = entry.getValue();
                if (!h.isActiveOrCoolingDown()) {
                    ensureSymbolSocket(entry.getKey());
                }
            }
        }

        WsSessionHandle market = marketSocket;
        if (market != null) {
            boolean watchlistIdle = nowMs - lastWatchlistAccessMs > TICKER_IDLE_CLOSE_MS && activeSymbols.isEmpty();
            if (watchlistIdle) {
                marketSocket = null;
                market.closeQuietly();
            } else if (wsEnabled && !market.isActiveOrCoolingDown()) {
                ensureMarketSocket();
            }
        }
    }

    void handleMarketStreamFrame(String jsonText) {
        try {
            JsonNode root = objectMapper.readTree(jsonText);
            String streamName = root.path("stream").asText("");
            JsonNode data = root.has("data") ? root.get("data") : root;
            if (!data.isArray()) {
                return;
            }
            long nowMs = System.currentTimeMillis();
            boolean isMarkStream = streamName.contains("markPrice");
            for (JsonNode item : data) {
                String sym = item.path("s").asText("");
                if (!sym.endsWith("USDT") || sym.contains("_")) {
                    continue;
                }
                LiveSymbolState state = getOrCreateState(sym);
                if (state == null) {
                    continue;
                }
                if (isMarkStream) {
                    state.applyMarkPriceUpdate(item, nowMs);
                } else {
                    state.apply24hrTickerUpdate(item, nowMs);
                }
            }
        } catch (Exception ignored) {
        }
    }

    void handleSymbolStreamFrame(String symbol, String jsonText) {
        try {
            JsonNode root = objectMapper.readTree(jsonText);
            String streamName = root.path("stream").asText("");
            JsonNode data = root.has("data") ? root.get("data") : root;
            LiveSymbolState state = getOrCreateState(symbol);
            if (state == null) {
                return;
            }
            long nowMs = System.currentTimeMillis();
            if (streamName.endsWith("@aggTrade")) {
                state.applyAggTrade(data, nowMs);
            } else if (streamName.endsWith("@bookTicker")) {
                state.applyBookTicker(data, nowMs);
            } else if (streamName.contains("@markPrice")) {
                state.applyMarkPriceUpdate(data, nowMs);
            }
        } catch (Exception ignored) {
        }
    }

    private LiveSymbolState getOrCreateState(String symbol) {
        if (symbol == null || !symbol.matches("^[A-Z0-9]{2,20}$")) {
            return null;
        }
        LiveSymbolState existing = liveStates.get(symbol);
        if (existing != null) {
            return existing;
        }
        if (liveStates.size() >= MAX_TRACKED_SYMBOLS) {
            return null;
        }
        return liveStates.computeIfAbsent(symbol, LiveSymbolState::new);
    }

    @PreDestroy
    public void shutdown() {
        for (SseSubscription sub : subscriptions) {
            closeSubscription(sub);
        }
        WsSessionHandle market = marketSocket;
        marketSocket = null;
        if (market != null) {
            market.closeQuietly();
        }
        for (WsSessionHandle h : symbolSockets.values()) {
            h.closeQuietly();
        }
        symbolSockets.clear();
        scheduler.shutdownNow();
    }

    public static String normalizeDecimalString(String val) {
        if (val == null || val.isBlank()) {
            return "0";
        }
        if (!val.contains(".")) {
            return val;
        }
        String trimmed = val.replaceAll("0+$", "");
        if (trimmed.endsWith(".")) {
            return trimmed + "00";
        }
        int dotIdx = trimmed.indexOf('.');
        if (dotIdx >= 0 && trimmed.length() - dotIdx - 1 == 1) {
            return trimmed + "0";
        }
        return trimmed;
    }

    private static final class SseSubscription {
        private final String symbol;
        private final SseEmitter emitter;
        private final Supplier<Map<String, Object>> fallbackSupplier;
        private final Runnable onRelease;
        private final String epoch = UUID.randomUUID().toString();
        private final AtomicLong seq = new AtomicLong(0L);
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private volatile long lastSentVersion = 0L;
        private volatile long lastSentAtMs = System.currentTimeMillis();
        private volatile long lastHeartbeatAtMs = System.currentTimeMillis();

        private SseSubscription(
                String symbol,
                SseEmitter emitter,
                Supplier<Map<String, Object>> fallbackSupplier,
                Runnable onRelease
        ) {
            this.symbol = symbol;
            this.emitter = emitter;
            this.fallbackSupplier = fallbackSupplier;
            this.onRelease = onRelease;
        }

        private synchronized void sendTick(Map<String, Object> payload, long stateVersion) throws IOException {
            if (closed.get()) {
                return;
            }
            long eventSeq = seq.incrementAndGet();
            emitter.send(SseEmitter.event()
                    .name("price.tick")
                    .id(epoch + ":" + eventSeq)
                    .data(payload));
            lastSentVersion = stateVersion;
        }

        private synchronized void sendHeartbeat() throws IOException {
            if (closed.get()) {
                return;
            }
            emitter.send(SseEmitter.event().comment("heartbeat"));
        }
    }

    private static final class WsSessionHandle {
        private final String key;
        private final URI uri;
        private volatile WebSocket webSocket;
        private volatile boolean connecting;
        private volatile boolean closed;
        private volatile long backoffMs;
        private volatile long nextRetryAtMs;

        private WsSessionHandle(String key, URI uri, long initialBackoffMs) {
            this.key = key;
            this.uri = uri;
            this.backoffMs = initialBackoffMs;
            this.nextRetryAtMs = 0L;
        }

        private boolean isActiveOrCoolingDown() {
            if (closed) {
                return false;
            }
            if (connecting || webSocket != null) {
                return true;
            }
            return System.currentTimeMillis() < nextRetryAtMs;
        }

        private long nextBackoffMs() {
            return backoffMs == 0L ? 2_000L : Math.min(backoffMs * 2L, 30_000L);
        }

        private void markDisconnected() {
            this.connecting = false;
            this.webSocket = null;
            this.backoffMs = nextBackoffMs();
            this.nextRetryAtMs = System.currentTimeMillis() + this.backoffMs;
        }

        private void closeQuietly() {
            this.closed = true;
            this.connecting = false;
            WebSocket ws = this.webSocket;
            this.webSocket = null;
            if (ws != null) {
                try {
                    ws.abort();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static final class LiveSymbolState {
        private final String symbol;
        private final AtomicLong version = new AtomicLong(0L);
        private volatile String lastPrice;
        private volatile String markPrice;
        private volatile String indexPrice;
        private volatile String bid;
        private volatile String ask;
        private volatile String fundingRate = "0.00010000";
        private volatile String priceChange = "0.00";
        private volatile String priceChangePercent = "0.00";
        private volatile String high24h;
        private volatile String low24h;
        private volatile String volume24h = "0";
        private volatile String quoteVolume24h = "0";
        private volatile long tradeCount = 0L;
        private volatile long sourceTimeMs = 0L;
        private volatile long receivedAtMs = 0L;
        private volatile long tickerUpdatedAtMs = 0L;

        private LiveSymbolState(String symbol) {
            this.symbol = symbol;
        }

        private synchronized void applySeed(Map<String, Object> payload) {
            if (lastPrice == null) lastPrice = strOrNull(payload.get("lastPrice"));
            if (markPrice == null) markPrice = strOrNull(payload.get("markPrice"));
            if (indexPrice == null) indexPrice = strOrNull(payload.get("indexPrice"));
            if (bid == null) bid = strOrNull(payload.get("bid"));
            if (ask == null) ask = strOrNull(payload.get("ask"));
            String f = strOrNull(payload.get("fundingRate"));
            if (f != null) fundingRate = f;
            String pc = strOrNull(payload.get("priceChange"));
            if (pc != null) priceChange = pc;
            String pcp = strOrNull(payload.get("priceChangePercent"));
            if (pcp != null) priceChangePercent = pcp;
            if (high24h == null) high24h = strOrNull(payload.get("high24h"));
            if (low24h == null) low24h = strOrNull(payload.get("low24h"));
            String v = strOrNull(payload.get("volume24h"));
            if (v != null) volume24h = v;
            String qv = strOrNull(payload.get("quoteVolume24h"));
            if (qv != null) quoteVolume24h = qv;
            if (receivedAtMs == 0L) {
                long now = System.currentTimeMillis();
                receivedAtMs = now;
                sourceTimeMs = now;
            }
        }

        private synchronized void applyAggTrade(JsonNode data, long nowMs) {
            String price = nonBlankNormalized(data.path("p").asText(""));
            if (price == null) {
                return;
            }
            this.lastPrice = price;
            this.sourceTimeMs = data.path("E").asLong(nowMs);
            this.receivedAtMs = nowMs;
            this.version.incrementAndGet();
        }

        private synchronized void applyBookTicker(JsonNode data, long nowMs) {
            String nextBid = nonBlankNormalized(data.path("b").asText(""));
            String nextAsk = nonBlankNormalized(data.path("a").asText(""));
            if (nextBid == null || nextAsk == null) {
                return;
            }
            this.bid = nextBid;
            this.ask = nextAsk;
            this.sourceTimeMs = data.path("E").asLong(nowMs);
            this.receivedAtMs = nowMs;
            this.version.incrementAndGet();
        }

        private synchronized void applyMarkPriceUpdate(JsonNode data, long nowMs) {
            String nextMark = nonBlankNormalized(data.path("p").asText(""));
            if (nextMark == null) {
                return;
            }
            this.markPrice = nextMark;
            String nextIndex = nonBlankNormalized(data.path("i").asText(""));
            if (nextIndex != null) {
                this.indexPrice = nextIndex;
            }
            String nextFunding = data.path("r").asText("");
            if (!nextFunding.isBlank()) {
                this.fundingRate = nextFunding;
            }
            this.sourceTimeMs = data.path("E").asLong(nowMs);
            this.receivedAtMs = nowMs;
            this.version.incrementAndGet();
        }

        private synchronized void apply24hrTickerUpdate(JsonNode data, long nowMs) {
            String nextLast = nonBlankNormalized(data.path("c").asText(""));
            if (nextLast != null) {
                this.lastPrice = nextLast;
            }
            String pc = nonBlankNormalized(data.path("p").asText(""));
            if (pc != null) this.priceChange = pc;
            String pcp = data.path("P").asText("");
            if (!pcp.isBlank()) this.priceChangePercent = pcp;
            String h = nonBlankNormalized(data.path("h").asText(""));
            if (h != null) this.high24h = h;
            String l = nonBlankNormalized(data.path("l").asText(""));
            if (l != null) this.low24h = l;
            String v = nonBlankNormalized(data.path("v").asText(""));
            if (v != null) this.volume24h = v;
            String qv = nonBlankNormalized(data.path("q").asText(""));
            if (qv != null) this.quoteVolume24h = qv;
            long n = data.path("n").asLong(0L);
            if (n > 0L) this.tradeCount = n;
            this.tickerUpdatedAtMs = nowMs;
            this.receivedAtMs = nowMs;
            this.sourceTimeMs = data.path("E").asLong(nowMs);
            this.version.incrementAndGet();
        }

        private synchronized Map<String, Object> toQuotePayloadIfFresh(long maxAgeMs, AtomicLong seqGen) {
            long nowMs = System.currentTimeMillis();
            if (receivedAtMs == 0L || (nowMs - receivedAtMs) > maxAgeMs) {
                return null;
            }
            if (lastPrice == null || markPrice == null || bid == null || ask == null) {
                return null;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("instrumentId", symbol);
            out.put("symbol", symbol);
            out.put("markPrice", markPrice);
            out.put("lastPrice", lastPrice);
            out.put("bid", bid);
            out.put("ask", ask);
            out.put("indexPrice", indexPrice != null ? indexPrice : markPrice);
            out.put("fundingRate", fundingRate);
            out.put("priceChange", priceChange);
            out.put("priceChangePercent", priceChangePercent);
            out.put("high24h", high24h != null ? high24h : lastPrice);
            out.put("low24h", low24h != null ? low24h : lastPrice);
            out.put("volume24h", volume24h);
            out.put("quoteVolume24h", quoteVolume24h);
            long srcMs = sourceTimeMs > 0L ? sourceTimeMs : nowMs;
            out.put("sourceTime", Instant.ofEpochMilli(srcMs).toString());
            out.put("receivedAt", Instant.ofEpochMilli(nowMs).toString());
            out.put("sequence", seqGen.incrementAndGet());
            out.put("quality", "FRESH");
            return out;
        }

        private static String nonBlankNormalized(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            return normalizeDecimalString(raw);
        }

        private static String strOrNull(Object val) {
            if (val == null) {
                return null;
            }
            String s = String.valueOf(val);
            return s.isBlank() ? null : s;
        }
    }
}
