package com.crypto.marketdataservice.api.controller;

import com.crypto.marketdataservice.infrastructure.client.BinanceFuturesStreamHub;
import com.crypto.marketdataservice.infrastructure.client.PublicMarketHttp;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MarketControllerFallbackAndRangeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void offlineGetQuoteNeverLeaksFake71430PriceWhenFixtureDisabled() {
        BinanceFuturesStreamHub hub = BinanceFuturesStreamHub.disabled(objectMapper);
        try {
            PublicMarketHttp offlineHttp = new PublicMarketHttp(URI.create("https://127.0.0.1:1"), objectMapper);
            MarketController controller = new MarketController(hub, false, offlineHttp, offlineHttp);

            ResponseStatusException ex = assertThrows(
                    ResponseStatusException.class,
                    () -> controller.getQuote("ETHUSDT")
            );
            assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        } finally {
            hub.shutdown();
        }
    }

    @Test
    void unknownInstrumentUuidThrowsNotFoundInsteadOfDefaultingToBtc() {
        BinanceFuturesStreamHub hub = BinanceFuturesStreamHub.disabled(objectMapper);
        try {
            PublicMarketHttp offlineHttp = new PublicMarketHttp(URI.create("https://127.0.0.1:1"), objectMapper);
            MarketController controller = new MarketController(hub, true, offlineHttp, offlineHttp);

            ResponseStatusException ex = assertThrows(
                    ResponseStatusException.class,
                    () -> controller.getQuote("99999999-9999-4999-8999-999999999999")
            );
            assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(ex.getReason()).isEqualTo("UNKNOWN_INSTRUMENT");
        } finally {
            hub.shutdown();
        }
    }

    @Test
    void offlineHistoricalCandlesRejectFakeWaveDataAndValidateTimeRange() {
        BinanceFuturesStreamHub hub = BinanceFuturesStreamHub.disabled(objectMapper);
        try {
            PublicMarketHttp offlineHttp = new PublicMarketHttp(URI.create("https://127.0.0.1:1"), objectMapper);
            MarketController controller = new MarketController(hub, true, offlineHttp, offlineHttp);

            ResponseStatusException badRange = assertThrows(
                    ResponseStatusException.class,
                    () -> controller.getCandles("BTCUSDT", "1m", 100, 2_000_000L, 1_000_000L)
            );
            assertThat(badRange.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

            ResponseStatusException unavailable = assertThrows(
                    ResponseStatusException.class,
                    () -> controller.getCandles("BTCUSDT", "1m", 100, 1_700_000_000_000L, 1_700_003_600_000L)
            );
            assertThat(unavailable.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        } finally {
            hub.shutdown();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void offlineListTickersReturnsEmptyWhenFixtureDisabled() {
        BinanceFuturesStreamHub hub = BinanceFuturesStreamHub.disabled(objectMapper);
        try {
            PublicMarketHttp offlineHttp = new PublicMarketHttp(URI.create("https://127.0.0.1:1"), objectMapper);
            MarketController controller = new MarketController(hub, false, offlineHttp, offlineHttp);

            Map<String, Object> result = controller.listTickers(null, 50);
            List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
            assertThat(items).isEmpty();
        } finally {
            hub.shutdown();
        }
    }
}
