package com.crypto.marketdataservice.infrastructure.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class BinanceFuturesStreamHubTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void aggTradeAndBookTickerProduceFreshQuoteAndCoalescedSseSequence() throws IOException {
        BinanceFuturesStreamHub hub = BinanceFuturesStreamHub.disabled(objectMapper);
        try {
            hub.seedQuote("BTCUSDT", Map.of(
                    "instrumentId", "BTCUSDT",
                    "symbol", "BTCUSDT",
                    "lastPrice", "90000.00",
                    "markPrice", "90001.00",
                    "bid", "89999.50",
                    "ask", "90000.50",
                    "quality", "FRESH"
            ));

            RecordingSseEmitter emitter = new RecordingSseEmitter();
            AtomicBoolean released = new AtomicBoolean(false);
            hub.subscribe("BTCUSDT", emitter, () -> hub.latestLiveQuote("BTCUSDT", 5_000L), () -> released.set(true));

            hub.handleSymbolStreamFrame("BTCUSDT", """
                    {"stream":"btcusdt@bookTicker","data":{"s":"BTCUSDT","b":"90123.10","a":"90123.30","E":1794615000000}}
                    """);
            hub.handleSymbolStreamFrame("BTCUSDT", """
                    {"stream":"btcusdt@aggTrade","data":{"s":"BTCUSDT","p":"90123.25","E":1794615000100}}
                    """);
            hub.flushSubscriptions();

            Map<String, Object> freshQuote = hub.latestLiveQuote("BTCUSDT", 5_000L);
            assertThat(freshQuote).isNotNull();
            assertThat(freshQuote.get("lastPrice")).isEqualTo("90123.25");
            assertThat(freshQuote.get("bid")).isEqualTo("90123.10");
            assertThat(freshQuote.get("ask")).isEqualTo("90123.30");
            assertThat(freshQuote.get("quality")).isEqualTo("FRESH");

            assertThat(emitter.sentPayloads).hasSizeGreaterThanOrEqualTo(2);
            String firstFrame = String.join("", emitter.sentPayloads.get(0));
            String secondFrame = String.join("", emitter.sentPayloads.get(1));
            assertThat(firstFrame).contains("event:price.tick").contains(":1\n");
            assertThat(secondFrame).contains("event:price.tick").contains(":2\n").contains("90123.25");

            hub.shutdown();
            assertThat(released.get()).isTrue();
        } finally {
            hub.shutdown();
        }
    }

    @Test
    void marketStreamOverlayUpdatesWatchlistTickersWithoutDroppingBaseMetadata() {
        BinanceFuturesStreamHub hub = BinanceFuturesStreamHub.disabled(objectMapper);
        try {
            hub.handleMarketStreamFrame("""
                    {"stream":"!ticker@arr","data":[{"s":"ETHUSDT","c":"3150.40","p":"50.40","P":"1.62","h":"3200.00","l":"3090.00","v":"125000","q":"390000000","n":950000}]}
                    """);

            List<Map<String, Object>> base = List.of(
                    Map.of("symbol", "ETHUSDT", "lastPrice", "3100.00", "priceChangePercent", "0.10"),
                    Map.of("symbol", "SOLUSDT", "lastPrice", "210.00", "priceChangePercent", "1.00")
            );

            List<Map<String, Object>> overlaid = hub.overlayTickers(base);
            assertThat(overlaid).hasSize(2);
            assertThat(overlaid.get(0).get("lastPrice")).isEqualTo("3150.40");
            assertThat(overlaid.get(0).get("priceChangePercent")).isEqualTo("1.62");
            assertThat(overlaid.get(0).get("quoteVolume")).isEqualTo("390000000");
            assertThat(overlaid.get(1).get("lastPrice")).isEqualTo("210.00");
        } finally {
            hub.shutdown();
        }
    }

    private static final class RecordingSseEmitter extends SseEmitter {
        final List<List<String>> sentPayloads = new ArrayList<>();

        RecordingSseEmitter() {
            super(120_000L);
        }

        @Override
        public synchronized void send(SseEventBuilder builder) {
            Set<ResponseBodyEmitter.DataWithMediaType> parts = builder.build();
            List<String> chunks = new ArrayList<>(parts.size());
            for (ResponseBodyEmitter.DataWithMediaType part : parts) {
                chunks.add(String.valueOf(part.getData()));
            }
            sentPayloads.add(chunks);
        }
    }
}
