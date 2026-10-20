package com.crypto.marketdataservice;

import com.crypto.marketdataservice.application.port.MarketSource;
import com.crypto.marketdataservice.infrastructure.cache.SnapshotCache;
import com.crypto.marketdataservice.infrastructure.client.PublicMarketHttp;
import com.crypto.marketdataservice.infrastructure.client.fixture.FixtureAdapter;
import com.crypto.marketdataservice.infrastructure.mapper.BinanceMetadataDecoder;
import com.crypto.marketdataservice.infrastructure.messaging.OutboxPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
class MarketDataB06Test {

    @Autowired
    JdbcTemplate db;

    @Autowired
    DataSource dataSource;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void migrateMainMarketDbSchemaForDbeaverInspection() {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .defaultSchema("market_db")
                .schemas("market_db")
                .createSchemas(true)
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();

        Integer tableCount = db.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'market_db' AND table_name = 'outbox'",
                Integer.class);
        assertEquals(1, tableCount);
    }

    @Test
    void fixtureAdapter_emitsSingleControlledTickAndListsInstrument() throws Exception {
        Clock fixed = Clock.fixed(Instant.parse("2026-02-01T12:00:00Z"), ZoneOffset.UTC);
        var adapter = new FixtureAdapter(fixed);

        assertEquals(1, adapter.listInstruments().size());
        assertEquals("BTCUSDT", adapter.listInstruments().getFirst().symbol());

        List<MarketSource.Mark> marks = new ArrayList<>();
        try (var ignored = adapter.connectMarks(List.of("BTCUSDT"), marks::add)) {
            assertEquals(1, marks.size());
            assertEquals("50000", marks.getFirst().price());
            assertEquals(fixed.instant(), marks.getFirst().sourceTime());
        }
    }

    @Test
    void binanceMetadataDecoder_decodesByFilterTypeAndPreservesContractIdentity() throws Exception {
        var sample = json.readTree("""
                {
                  "symbols": [
                    {
                      "symbol": "BTCUSDT",
                      "contractType": "PERPETUAL",
                      "onboardDate": 1569398400000,
                      "baseAsset": "BTC",
                      "quoteAsset": "USDT",
                      "marginAsset": "USDT",
                      "status": "TRADING",
                      "filters": [
                        {"filterType": "MIN_NOTIONAL", "notional": "5"},
                        {"filterType": "LOT_SIZE", "stepSize": "0.001"},
                        {"filterType": "PRICE_FILTER", "tickSize": "0.10"}
                      ]
                    }
                  ]
                }
                """);

        var list = BinanceMetadataDecoder.decode(sample);
        assertEquals(1, list.size());
        var inst = list.getFirst();
        assertEquals("BINANCE", inst.exchange());
        assertEquals("BTCUSDT", inst.symbol());
        assertEquals("PERPETUAL:1569398400000", inst.contractIdentity());
        assertEquals("0.10", inst.tick());
        assertEquals("0.001", inst.step());
        assertEquals("5", inst.minNotional());
    }

    @Test
    void publicMarketHttp_rejectsNonHttpsAndHostChange() {
        assertThrows(IllegalArgumentException.class,
                () -> new PublicMarketHttp(URI.create("http://fapi.binance.com"), json));

        var client = new PublicMarketHttp(URI.create("https://fapi.binance.com"), json);
        assertThrows(IllegalArgumentException.class,
                () -> client.get("https://evil.example.com/fapi/v1/exchangeInfo"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void snapshotCache_setsTtlAndGracefullyHandlesRedisDown() {
        StringRedisTemplate redis = Mockito.mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = Mockito.mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);

        var cache = new SnapshotCache(redis);
        UUID id = UUID.randomUUID();

        cache.put(id, "mark", "{\"price\":\"50000\"}");
        verify(ops).set("market:mark:" + id, "{\"price\":\"50000\"}", Duration.ofSeconds(5));

        cache.put(id, "quote", "{\"bid\":\"49999\"}");
        verify(ops).set("market:quote:" + id, "{\"bid\":\"49999\"}", Duration.ofSeconds(2));

        // Redis kapalı / erişilemez senaryosu
        when(ops.get(anyString())).thenThrow(new DataAccessResourceFailureException("REDIS_DOWN"));
        assertTrue(cache.get(id, "mark").isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void outboxPublisher_claimsAndMarksPublishedInPostgres() {
        UUID eventId = UUID.randomUUID();
        db.update("""
                INSERT INTO outbox(event_id, topic, event_key, payload, created_at)
                VALUES (?, 'market.mark.v2', 'BTCUSDT', '{"eventId":"1"}'::jsonb, now())
                """, eventId);

        KafkaTemplate<String, String> kafka = Mockito.mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        var publisher = new OutboxPublisher(db, kafka);
        publisher.publish();

        Integer publishedCount = db.queryForObject(
                "SELECT count(*) FROM outbox WHERE event_id = ? AND published_at IS NOT NULL AND lease_until IS NULL AND attempts = 1",
                Integer.class, eventId);
        assertEquals(1, publishedCount);
    }
}

