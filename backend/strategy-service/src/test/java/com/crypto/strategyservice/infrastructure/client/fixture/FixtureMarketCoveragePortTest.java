package com.crypto.strategyservice.infrastructure.client.fixture;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class FixtureMarketCoveragePortTest {

    private static final UUID BTC_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final Instant NOW = Instant.parse("2026-11-13T11:00:00Z");

    private HttpServer server;
    private FixtureMarketCoveragePort port;
    private final AtomicReference<String> lastCandlesQuery = new AtomicReference<>("");
    private final AtomicInteger candlesStatus = new AtomicInteger(200);
    private final AtomicReference<String> candlesBody = new AtomicReference<>("{\"bars\":[]}");

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/market/quotes", exchange -> {
            byte[] bytes = "{\"instrumentId\":\"BTCUSDT\",\"markPrice\":\"90000.00\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/api/v1/market/candles", exchange -> {
            lastCandlesQuery.set(exchange.getRequestURI().getQuery());
            byte[] bytes = candlesBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(candlesStatus.get(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        port = new FixtureMarketCoveragePort(baseUrl);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void computesRealAtr14FromLiveCandlesAndMapsHorizonInterval() {
        var barsJson = new StringBuilder("{\"bars\":[");
        for (int i = 0; i < 16; i++) {
            if (i > 0) {
                barsJson.append(',');
            }
            // Close = 90000 throughout, Low = 89500, High = 90500 => True Range = 1000 per bar -> ATR(14) = 1000.0000
            barsJson.append("{\"open\":\"90000\",\"high\":\"90500\",\"low\":\"89500\",\"close\":\"90000\"}");
        }
        barsJson.append("]}");
        candlesStatus.set(200);
        candlesBody.set(barsJson.toString());

        var eval = port.evaluate(BTC_ID, 3600, NOW);

        assertEquals(0, new BigDecimal("90000.00").compareTo(eval.referencePrice()));
        assertEquals(0, new BigDecimal("1000").compareTo(eval.verifiedAtr()));
        assertTrue(lastCandlesQuery.get().contains("interval=1h"));
        assertTrue(lastCandlesQuery.get().contains("instrumentId=BTCUSDT"));
        assertEquals("1m", FixtureMarketCoveragePort.mapHorizonToInterval(60));
        assertEquals("15m", FixtureMarketCoveragePort.mapHorizonToInterval(900));
        assertEquals("1h", FixtureMarketCoveragePort.mapHorizonToInterval(3600));
        assertEquals("1d", FixtureMarketCoveragePort.mapHorizonToInterval(86400));
    }

    @Test
    void fallsBackToVolatilityProxyWhenCandlesUnavailableAndClampsOutliers() {
        candlesStatus.set(503);
        candlesBody.set("{\"error\":\"unavailable\"}");

        var fallbackEval = port.evaluate(BTC_ID, 900, NOW);
        // 90000 * 0.008 = 720.00
        assertEquals(0, new BigDecimal("720").compareTo(fallbackEval.verifiedAtr()));

        // Tiny ATR clamps up to 0.05% of live price = 45.00
        var flatBars = "{\"bars\":["
                + "{\"high\":\"90001\",\"low\":\"90000\",\"close\":\"90000\"},"
                + "{\"high\":\"90001\",\"low\":\"90000\",\"close\":\"90000\"}"
                + "]}";
        candlesStatus.set(200);
        candlesBody.set(flatBars);
        var clampedMin = port.evaluate(BTC_ID, 60, NOW);
        assertEquals(0, new BigDecimal("45").compareTo(clampedMin.verifiedAtr()));

        // Extreme spike ATR clamps down to 12% of live price = 10800.00
        var spikeBars = "{\"bars\":["
                + "{\"high\":\"100000\",\"low\":\"60000\",\"close\":\"90000\"},"
                + "{\"high\":\"110000\",\"low\":\"60000\",\"close\":\"90000\"}"
                + "]}";
        candlesBody.set(spikeBars);
        var clampedMax = port.evaluate(BTC_ID, 86400, NOW);
        assertEquals(0, new BigDecimal("10800").compareTo(clampedMax.verifiedAtr()));
    }
}
