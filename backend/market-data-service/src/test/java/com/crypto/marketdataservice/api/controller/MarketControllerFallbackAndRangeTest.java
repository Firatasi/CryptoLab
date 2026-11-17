package com.crypto.marketdataservice.api.controller;

import com.crypto.marketdataservice.infrastructure.client.BinanceFuturesStreamHub;
import com.crypto.marketdataservice.infrastructure.client.PublicMarketHttp;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

    @Test
    void getCandlesRejectsPathTraversalAndInvalidCharactersInInstrumentIdWithBadRequest() throws Exception {
        BinanceFuturesStreamHub hub = BinanceFuturesStreamHub.disabled(objectMapper);
        try {
            PublicMarketHttp mockPrimaryHttp = Mockito.mock(PublicMarketHttp.class);
            PublicMarketHttp mockVisionHttp = Mockito.mock(PublicMarketHttp.class);
            MarketController controller = new MarketController(hub, false, mockPrimaryHttp, mockVisionHttp);
            MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

            List<String> maliciousOrInvalidInputs = List.of(
                    "../etc/passwd",
                    "BTCUSDT/../admin",
                    "BTC/USDT",
                    "BTC;DROP",
                    "BTCUSDT?recvWindow=5000",
                    "BTC%2FUSDT",
                    "1-2-3-4-5",
                    "BTCUSDT\n",
                    "A",
                    "TOOLONGINSTRUMENTSYMBOL123"
            );

            for (String invalid : maliciousOrInvalidInputs) {
                ResponseStatusException ex = assertThrows(
                        ResponseStatusException.class,
                        () -> controller.getCandles(invalid, "15m", 100, null, null),
                        "Expected 400 BAD_REQUEST for instrumentId=" + invalid
                );
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(ex.getReason()).isEqualTo("INVALID_INSTRUMENT");

                mockMvc.perform(get("/api/v1/market/candles")
                                .param("instrumentId", invalid)
                                .param("interval", "15m"))
                        .andExpect(status().isBadRequest());
            }

            verifyNoInteractions(mockPrimaryHttp, mockVisionHttp);

            // Geçerli sembol ve UUID beyaz listeden geçer (sağlayıcı mock hata verince 503'e düşer)
            mockMvc.perform(get("/api/v1/market/candles")
                            .param("instrumentId", " ethusdt ")
                            .param("interval", "15m"))
                    .andExpect(status().isServiceUnavailable());
            mockMvc.perform(get("/api/v1/market/candles")
                            .param("instrumentId", "11111111-1111-4111-8111-111111111111")
                            .param("interval", "15m"))
                    .andExpect(status().isServiceUnavailable());
        } finally {
            hub.shutdown();
        }
    }
}
