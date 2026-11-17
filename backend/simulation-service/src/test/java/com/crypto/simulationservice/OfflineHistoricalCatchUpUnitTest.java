package com.crypto.simulationservice;

import com.crypto.financecore.finance.Decimal;
import com.crypto.simulationservice.api.controller.SessionController;
import com.crypto.simulationservice.application.service.AiPreviewService;
import com.crypto.simulationservice.application.service.CreateSession;
import com.crypto.simulationservice.application.service.LiquidationMonitorService;
import com.crypto.simulationservice.application.service.OrderExecutionService;
import com.crypto.simulationservice.application.service.SimulationMarketCatalog;
import com.crypto.simulationservice.application.service.SimulationMarketCatalog.HistoricalCandle;
import com.crypto.simulationservice.application.service.SimulationMarketCatalog.HistoricalCandleProvider;
import com.crypto.simulationservice.application.service.SimulationReportingService;
import com.crypto.simulationservice.infrastructure.streaming.SessionStreamRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.oauth2.jwt.Jwt;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OfflineHistoricalCatchUpUnitTest {

    private static final UUID BTC_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID RULE_PROFILE = UUID.fromString("88888888-8888-4888-8888-888888888888");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void unknownDynamicInstrumentNeverDefaultsToBtcAndRecoversFromPersistedCache() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        SimulationMarketCatalog offlineCatalog = new SimulationMarketCatalog(
                OBJECT_MAPPER, "http://127.0.0.1:1", jdbc
        );

        UUID unknownId = UUID.nameUUIDFromBytes("UNMAPPED_ZERO_TEST".getBytes());
        assertTrue(offlineCatalog.resolveKnownSymbol(unknownId).isEmpty());
        assertTrue(offlineCatalog.fetchLiveMarkPriceIfAvailable(unknownId).isEmpty());
        assertTrue(offlineCatalog.resolveSymbol(unknownId).startsWith("UNKNOWN-"));

        // Dinamik sembol eklendiğinde kalıcı instrument_symbol_cache tablosuna yazılmalı
        UUID wifId = offlineCatalog.resolveInstrumentUuid("WIFUSDT");
        assertTrue(jdbc.updates.stream().anyMatch(u ->
                u.sql().contains("instrument_symbol_cache") && List.of(u.args()).contains("WIFUSDT")));
        assertEquals(Optional.of("WIFUSDT"), offlineCatalog.resolveKnownSymbol(wifId));

        // Soğuk yeniden başlatma simülasyonu: bellekten farklı yeni bir sembol DB cache'den okunabilmeli
        UUID persistedOnlyId = UUID.randomUUID();
        jdbc.symbolByInstrumentId = Map.of(persistedOnlyId, "ALTCOINXUSDT");
        SimulationMarketCatalog rebootedCatalog = new SimulationMarketCatalog(
                OBJECT_MAPPER, "http://127.0.0.1:1", jdbc
        );
        assertEquals(Optional.of("ALTCOINXUSDT"), rebootedCatalog.resolveKnownSymbol(persistedOnlyId));
        assertEquals("ALTCOINXUSDT", rebootedCatalog.resolveSymbol(persistedOnlyId));
    }

    @Test
    void fifteenMinuteTradeClosedDuringOneDayOutageSettlesAtHistoricalTakeProfitTime() {
        Instant openedAt = Instant.parse("2026-11-10T10:00:00Z");
        Instant deadlineAt = Instant.parse("2026-11-10T10:15:00Z");
        Instant now = Instant.parse("2026-11-11T10:00:00Z"); // 1 gün sonra PC açılıyor
        UUID ownerId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID posId = UUID.randomUUID();

        PositionFixture pos = new PositionFixture(
                posId, sessionId, BTC_ID, "LONG",
                new BigDecimal("0.1"), new BigDecimal("50000"), new BigDecimal("500"),
                new BigDecimal("49000"), new BigDecimal("51500"),
                openedAt, deadlineAt, "CLOSE_AT_DEADLINE",
                new BigDecimal("2.00"), BigDecimal.ZERO
        );

        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        jdbc.openPositions.add(pos);

        SimulationMarketCatalog catalog = new SimulationMarketCatalog(OBJECT_MAPPER, "http://127.0.0.1:1");
        Clock fixedClock = Clock.fixed(now, ZoneOffset.UTC);
        SimulationReportingService service = new SimulationReportingService(jdbc, catalog, fixedClock, true);

        Instant tpBarClose = Instant.parse("2026-11-10T10:08:59Z");
        List<HistoricalCandle> bars = List.of(
                bar("2026-11-10T10:01:00Z", "2026-11-10T10:01:59Z", "50000", "50400", "49900", "50300"),
                bar("2026-11-10T10:08:00Z", "2026-11-10T10:08:59Z", "50300", "51650", "50250", "51600"),
                bar("2026-11-11T09:59:00Z", "2026-11-11T09:59:59Z", "46000", "46100", "45500", "45800")
        );

        // Ertesi gün canlı fiyat 45800 olsa bile 10:08'de TP vurduğu için kârla kapanmalı
        var events = service.processAutomaticExits(
                ownerId,
                sessionId,
                id -> Optional.of(new BigDecimal("45800")),
                (sym, iv, start, end) -> bars,
                now
        );

        assertEquals(1, events.size());
        var ev = events.getFirst();
        assertEquals(SimulationReportingService.AutoExitReason.TAKE_PROFIT, ev.reason());
        assertEquals(0, new BigDecimal("51500").compareTo(ev.markPrice()));
        assertEquals(tpBarClose, ev.closedAt());

        // Cüzdana yatan tutar ve commands denetim zamanı tarihsel kapanışla eşleşmeli
        assertTrue(jdbc.updates.stream().anyMatch(u ->
                u.sql().contains("UPDATE positions")
                        && u.args()[0].equals("CLOSED")
                        && ((Timestamp) u.args()[1]).toInstant().equals(tpBarClose)));
        assertTrue(jdbc.updates.stream().anyMatch(u ->
                u.sql().contains("INSERT INTO commands")
                        && u.args()[3].equals("TAKE_PROFIT")
                        && ((Timestamp) u.args()[4]).toInstant().equals(tpBarClose)));
    }

    @Test
    void ambiguousBarSelectsConservativeStopLossAndGapOpenFill() {
        Instant openedAt = Instant.parse("2026-11-10T10:00:00Z");
        Instant deadlineAt = Instant.parse("2026-11-10T10:15:00Z");
        Instant now = Instant.parse("2026-11-10T12:00:00Z");
        UUID ownerId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        PositionFixture pos = new PositionFixture(
                UUID.randomUUID(), sessionId, BTC_ID, "LONG",
                new BigDecimal("0.1"), new BigDecimal("50000"), new BigDecimal("500"),
                new BigDecimal("49000"), new BigDecimal("51500"),
                openedAt, deadlineAt, "REEVALUATE",
                new BigDecimal("2.00"), BigDecimal.ZERO
        );

        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        jdbc.openPositions.add(pos);

        SimulationReportingService service = new SimulationReportingService(
                jdbc, new SimulationMarketCatalog(OBJECT_MAPPER, "http://127.0.0.1:1"),
                Clock.fixed(now, ZoneOffset.UTC), true
        );

        // Aynı mumda hem SL (49000) hem TP (51500) geçildi + open gap yok -> STOP_LOSS @ 49000
        List<HistoricalCandle> ambiguousBars = List.of(
                bar("2026-11-10T10:05:00Z", "2026-11-10T10:05:59Z", "49500", "52000", "48500", "51800")
        );
        var evs = service.processAutomaticExits(
                ownerId, sessionId, id -> Optional.empty(), (s, i, a, b) -> ambiguousBars, now
        );
        assertEquals(1, evs.size());
        assertEquals(SimulationReportingService.AutoExitReason.STOP_LOSS, evs.getFirst().reason());
        assertEquals(0, new BigDecimal("49000").compareTo(evs.getFirst().markPrice()));

        // Gap-down open (48700 < stopLoss 49000) -> gerçekçi dolum open fiyatı 48700
        jdbc.updates.clear();
        List<HistoricalCandle> gapBars = List.of(
                bar("2026-11-10T10:06:00Z", "2026-11-10T10:06:59Z", "48700", "49200", "48600", "49100")
        );
        var gapEvs = service.processAutomaticExits(
                ownerId, sessionId, id -> Optional.empty(), (s, i, a, b) -> gapBars, now
        );
        assertEquals(1, gapEvs.size());
        assertEquals(SimulationReportingService.AutoExitReason.STOP_LOSS, gapEvs.getFirst().reason());
        assertEquals(0, new BigDecimal("48700").compareTo(gapEvs.getFirst().markPrice()));
    }

    @Test
    void liquidationTakesPriorityOverStopLossWhenFlashCrashHitsBothInSameCandle() {
        Instant openedAt = Instant.parse("2026-11-10T10:00:00Z");
        Instant deadlineAt = Instant.parse("2026-11-10T11:00:00Z");
        Instant now = Instant.parse("2026-11-10T12:00:00Z");
        UUID ownerId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        // q=1, entry=50000, margin=1000, MMR=0.5% (~49246 liq); SL 49100 likidasyon sonrasındaysa önce tasfiye olur
        PositionFixture pos = new PositionFixture(
                UUID.randomUUID(), sessionId, BTC_ID, "LONG",
                new BigDecimal("1"), new BigDecimal("50000"), new BigDecimal("1000"),
                new BigDecimal("49100"), new BigDecimal("52000"),
                openedAt, deadlineAt, "CLOSE_AT_DEADLINE",
                BigDecimal.ZERO, BigDecimal.ZERO
        );

        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        jdbc.openPositions.add(pos);

        SimulationReportingService service = new SimulationReportingService(
                jdbc, new SimulationMarketCatalog(OBJECT_MAPPER, "http://127.0.0.1:1"),
                Clock.fixed(now, ZoneOffset.UTC), true
        );

        List<HistoricalCandle> crashBars = List.of(
                bar("2026-11-10T10:12:00Z", "2026-11-10T10:12:59Z", "49800", "49900", "48000", "48500")
        );
        var evs = service.processAutomaticExits(
                ownerId, sessionId, id -> Optional.empty(), (s, i, a, b) -> crashBars, now
        );

        assertEquals(1, evs.size());
        assertEquals(SimulationReportingService.AutoExitReason.LIQUIDATED, evs.getFirst().reason());
        assertEquals(Instant.parse("2026-11-10T10:12:59Z"), evs.getFirst().closedAt());
    }

    @Test
    void expiredCloseAtDeadlineLocksProfitAtDeadlineCandleAndPreservesOpenLossRule() {
        Instant openedAt = Instant.parse("2026-11-10T10:00:00Z");
        Instant deadlineAt = Instant.parse("2026-11-10T10:15:00Z");
        Instant now = Instant.parse("2026-11-11T10:00:00Z");
        UUID ownerId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        // 15 dakikalık SL/TP'siz işlem vadesinde net kârda -> 10:15 mum kapanışında kilitlenmeli
        PositionFixture profitablePos = new PositionFixture(
                UUID.randomUUID(), sessionId, BTC_ID, "LONG",
                new BigDecimal("0.1"), new BigDecimal("50000"), new BigDecimal("500"),
                null, null,
                openedAt, deadlineAt, "CLOSE_AT_DEADLINE",
                new BigDecimal("2.00"), BigDecimal.ZERO
        );

        RecordingJdbcTemplate profitJdbc = new RecordingJdbcTemplate();
        profitJdbc.openPositions.add(profitablePos);
        SimulationReportingService profitService = new SimulationReportingService(
                profitJdbc, new SimulationMarketCatalog(OBJECT_MAPPER, "http://127.0.0.1:1"),
                Clock.fixed(now, ZoneOffset.UTC), true
        );

        List<HistoricalCandle> profitBars = List.of(
                bar("2026-11-10T10:14:00Z", "2026-11-10T10:14:59Z", "50500", "50700", "50400", "50600"),
                bar("2026-11-10T10:15:00Z", "2026-11-10T10:15:59Z", "50600", "50750", "50550", "50650"),
                bar("2026-11-11T09:59:00Z", "2026-11-11T09:59:59Z", "47000", "47200", "46900", "47000")
        );
        var profitEvents = profitService.processAutomaticExits(
                ownerId, sessionId, id -> Optional.of(new BigDecimal("47000")), (s, i, a, b) -> profitBars, now
        );
        assertEquals(1, profitEvents.size());
        assertEquals(SimulationReportingService.AutoExitReason.TAKE_PROFIT, profitEvents.getFirst().reason());
        assertEquals(0, new BigDecimal("50650").compareTo(profitEvents.getFirst().markPrice()));
        assertEquals(Instant.parse("2026-11-10T10:15:59Z"), profitEvents.getFirst().closedAt());

        // Zarardaki CLOSE_AT_DEADLINE pozisyonu sistem kuralı gereği zorla kapatılmaz, izleme imleci güncellenir
        PositionFixture losingPos = new PositionFixture(
                UUID.randomUUID(), sessionId, BTC_ID, "LONG",
                new BigDecimal("0.1"), new BigDecimal("50000"), new BigDecimal("500"),
                null, null,
                openedAt, deadlineAt, "CLOSE_AT_DEADLINE",
                new BigDecimal("2.00"), BigDecimal.ZERO
        );
        RecordingJdbcTemplate lossJdbc = new RecordingJdbcTemplate();
        lossJdbc.openPositions.add(losingPos);
        SimulationReportingService lossService = new SimulationReportingService(
                lossJdbc, new SimulationMarketCatalog(OBJECT_MAPPER, "http://127.0.0.1:1"),
                Clock.fixed(now, ZoneOffset.UTC), true
        );
        List<HistoricalCandle> lossBars = List.of(
                bar("2026-11-10T10:15:00Z", "2026-11-10T10:15:59Z", "49800", "49900", "49600", "49700")
        );
        var lossEvents = lossService.processAutomaticExits(
                ownerId, sessionId, id -> Optional.of(new BigDecimal("49700")), (s, i, a, b) -> lossBars, now
        );
        assertTrue(lossEvents.isEmpty());
        assertTrue(lossJdbc.updates.stream().anyMatch(u -> u.sql().contains("position_monitor_state")));
    }

    @Test
    void partialEntryCandleIgnoresPreEntryWickAndShortLiveTickSkipsHistoricalFetch() {
        Instant openedAt = Instant.parse("2026-11-10T10:00:35Z");
        Instant now = Instant.parse("2026-11-10T10:10:00Z");
        UUID ownerId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        PositionFixture pos = new PositionFixture(
                UUID.randomUUID(), sessionId, BTC_ID, "LONG",
                new BigDecimal("0.1"), new BigDecimal("50000"), new BigDecimal("500"),
                new BigDecimal("49000"), new BigDecimal("52000"),
                openedAt, Instant.parse("2026-11-10T10:15:00Z"), "CLOSE_AT_DEADLINE",
                BigDecimal.ZERO, BigDecimal.ZERO
        );
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        jdbc.openPositions.add(pos);

        SimulationReportingService service = new SimulationReportingService(
                jdbc, new SimulationMarketCatalog(OBJECT_MAPPER, "http://127.0.0.1:1"),
                Clock.fixed(now, ZoneOffset.UTC), true
        );

        // 10:00 barının fitili (48500) pozisyon açılmadan önce oluşmuş olabilir; kapanışı (50100) güvenliyse stop etmemeli
        List<HistoricalCandle> entryCandleBars = List.of(
                bar("2026-11-10T10:00:00Z", "2026-11-10T10:00:59Z", "48600", "50200", "48500", "50100")
        );
        var partialEvents = service.processAutomaticExits(
                ownerId, sessionId, id -> Optional.of(new BigDecimal("50100")), (s, i, a, b) -> entryCandleBars, now
        );
        assertTrue(partialEvents.isEmpty());

        // Son değerlendirme 5 saniye önce yapıldıysa (<= 90s), tarihsel mum sağlayıcısı hiç çağrılmaz
        jdbc.lastEvaluatedByPosition = Map.of(pos.positionId(), now.minusSeconds(5));
        AtomicInteger historyCalls = new AtomicInteger(0);
        HistoricalCandleProvider countingProvider = (s, i, a, b) -> {
            historyCalls.incrementAndGet();
            return List.of();
        };
        var liveEvents = service.processAutomaticExits(
                ownerId, sessionId, id -> Optional.of(new BigDecimal("50100")), countingProvider, now
        );
        assertTrue(liveEvents.isEmpty());
        assertEquals(0, historyCalls.get());
    }

    @Test
    @SuppressWarnings("unchecked")
    void pendingLimitOrderReservedMarginCountsInSessionEquityAndRespectsDisabledMonitor() {
        UUID ownerId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        Jwt jwt = Jwt.withTokenValue("test-jwt").header("alg", "none").subject(ownerId.toString()).build();

        SimulationReportingService mockReporting = mock(SimulationReportingService.class);
        LiquidationMonitorService disabledMonitor = new LiquidationMonitorService(
                mockReporting, new SessionStreamRegistry(), Clock.systemUTC(), false
        );
        assertTrue(disabledMonitor.checkAndPublishIfEnabled(ownerId, sessionId).isEmpty());
        verifyNoInteractions(mockReporting);

        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        jdbc.sessionSummaryRow = new SessionSummaryFixture(
                sessionId,
                "Limit Wallet",
                "LIVE_PAPER",
                "RUNNING",
                new BigDecimal("10000"),
                new BigDecimal("8000"),
                BigDecimal.ZERO,
                new BigDecimal("2000"), // Bekleyen LIMIT emir rezervi
                RULE_PROFILE,
                UUID.fromString("99999999-9999-4999-8999-999999999999"),
                1L,
                Instant.parse("2026-11-10T10:00:00Z")
        );

        SessionController controller = new SessionController(
                mock(CreateSession.class),
                mock(AiPreviewService.class),
                new SimulationMarketCatalog(OBJECT_MAPPER, "http://127.0.0.1:1"),
                mockReporting,
                disabledMonitor,
                mock(OrderExecutionService.class),
                new SessionStreamRegistry(),
                jdbc,
                OBJECT_MAPPER,
                Clock.systemUTC()
        );

        Map<String, Object> resp = controller.listSessions(jwt);
        List<Map<String, Object>> items = (List<Map<String, Object>>) resp.get("items");
        assertEquals(1, items.size());
        Map<String, Object> wallet = items.getFirst();

        // 8000 kullanılabilir + 2000 limit rezervi = 10000 özkaynak -> 0 sahte zarar!
        assertEquals("10000", Decimal.wire(new BigDecimal((String) wallet.get("equity"))));
        assertEquals("0", wallet.get("realizedPnl"));
        assertEquals("0", wallet.get("netPnl"));
        assertEquals("0", wallet.get("netPnlPercent"));
        assertEquals("8000", wallet.get("availableBalance"));
    }

    private static HistoricalCandle bar(
            String openTime, String closeTime, String open, String high, String low, String close
    ) {
        return new HistoricalCandle(
                Instant.parse(openTime),
                Instant.parse(closeTime),
                new BigDecimal(open),
                new BigDecimal(high),
                new BigDecimal(low),
                new BigDecimal(close),
                true
        );
    }

    private record PositionFixture(
            UUID positionId,
            UUID sessionId,
            UUID instrumentId,
            String side,
            BigDecimal q,
            BigDecimal entryPrice,
            BigDecimal margin,
            BigDecimal stopLoss,
            BigDecimal takeProfit,
            Instant openedAt,
            Instant deadlineAt,
            String expiryPolicy,
            BigDecimal openFee,
            BigDecimal settledFunding
    ) {}

    private record SessionSummaryFixture(
            UUID id,
            String name,
            String mode,
            String status,
            BigDecimal initialDeposit,
            BigDecimal available,
            BigDecimal isolated,
            BigDecimal reserved,
            UUID ruleProfileId,
            UUID riskPolicyId,
            long version,
            Instant createdAt
    ) {}

    private record SqlUpdate(String sql, Object[] args) {}

    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        final List<PositionFixture> openPositions = new ArrayList<>();
        final List<SqlUpdate> updates = new ArrayList<>();
        Map<UUID, String> symbolByInstrumentId = Map.of();
        Map<UUID, Instant> lastEvaluatedByPosition = Map.of();
        SessionSummaryFixture sessionSummaryRow;

        @Override
        public void execute(String sql) {
        }

        @Override
        public int update(String sql, Object... args) {
            updates.add(new SqlUpdate(sql, args != null ? args : new Object[0]));
            return 1;
        }

        @Override
        public <T> List<T> queryForList(String sql, Class<T> elementType, Object... args) {
            if (sql.contains("instrument_symbol_cache") && args != null && args.length > 0 && args[0] instanceof UUID id) {
                String sym = symbolByInstrumentId.get(id);
                return sym != null ? List.of(elementType.cast(sym)) : List.of();
            }
            return List.of();
        }

        @Override
        public void query(String sql, RowCallbackHandler rch, Object... args) {
            if (sql.contains("position_monitor_state") && !lastEvaluatedByPosition.isEmpty()) {
                for (Map.Entry<UUID, Instant> entry : lastEvaluatedByPosition.entrySet()) {
                    try {
                        rch.processRow(monitorStateResultSet(entry.getKey(), entry.getValue()));
                    } catch (SQLException ex) {
                        throw new IllegalStateException(ex);
                    }
                }
            }
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            List<T> result = new ArrayList<>();
            try {
                if (sql.contains("FROM simulation_sessions s") && sql.contains("reserved_balance")
                        && sessionSummaryRow != null) {
                    result.add(rowMapper.mapRow(sessionSummaryResultSet(sessionSummaryRow), 0));
                } else if (sql.contains("FROM positions p")) {
                    for (int i = 0; i < openPositions.size(); i++) {
                        result.add(rowMapper.mapRow(positionResultSet(openPositions.get(i)), i));
                    }
                }
            } catch (SQLException ex) {
                throw new IllegalStateException(ex);
            }
            return result;
        }

        private static ResultSet monitorStateResultSet(UUID pid, Instant evaluatedAt) throws SQLException {
            ResultSet rs = mock(ResultSet.class);
            when(rs.getObject("position_id", UUID.class)).thenReturn(pid);
            when(rs.getTimestamp("last_evaluated_at")).thenReturn(Timestamp.from(evaluatedAt));
            return rs;
        }

        private static ResultSet positionResultSet(PositionFixture pos) throws SQLException {
            ResultSet rs = mock(ResultSet.class);
            when(rs.getObject("id", UUID.class)).thenReturn(pos.positionId());
            when(rs.getObject("session_id", UUID.class)).thenReturn(pos.sessionId());
            when(rs.getObject("instrument_id", UUID.class)).thenReturn(pos.instrumentId());
            when(rs.getString("side")).thenReturn(pos.side());
            when(rs.getBigDecimal("q")).thenReturn(pos.q());
            when(rs.getBigDecimal("entry_price")).thenReturn(pos.entryPrice());
            when(rs.getBigDecimal("initial_margin_remaining")).thenReturn(pos.margin());
            when(rs.getBigDecimal("stop_loss")).thenReturn(pos.stopLoss());
            when(rs.getBigDecimal("take_profit")).thenReturn(pos.takeProfit());
            when(rs.getObject("rule_profile_id", UUID.class)).thenReturn(RULE_PROFILE);
            when(rs.getBigDecimal("open_fee")).thenReturn(pos.openFee());
            when(rs.getBigDecimal("settled_funding")).thenReturn(pos.settledFunding());
            when(rs.getTimestamp("opened_at")).thenReturn(Timestamp.from(pos.openedAt()));
            when(rs.getTimestamp("deadline_at"))
                    .thenReturn(pos.deadlineAt() != null ? Timestamp.from(pos.deadlineAt()) : null);
            when(rs.getString("expiry_policy")).thenReturn(pos.expiryPolicy());
            return rs;
        }

        private static ResultSet sessionSummaryResultSet(SessionSummaryFixture s) throws SQLException {
            ResultSet rs = mock(ResultSet.class);
            when(rs.getObject("id", UUID.class)).thenReturn(s.id());
            when(rs.getString("session_name")).thenReturn(s.name());
            when(rs.getString("mode")).thenReturn(s.mode());
            when(rs.getString("status")).thenReturn(s.status());
            when(rs.getBigDecimal("initial_deposit")).thenReturn(s.initialDeposit());
            when(rs.getBigDecimal("available_balance")).thenReturn(s.available());
            when(rs.getBigDecimal("isolated_margin")).thenReturn(s.isolated());
            when(rs.getBigDecimal("reserved_balance")).thenReturn(s.reserved());
            when(rs.getObject("rule_profile_id", UUID.class)).thenReturn(s.ruleProfileId());
            when(rs.getObject("risk_policy_id", UUID.class)).thenReturn(s.riskPolicyId());
            when(rs.getLong("version")).thenReturn(s.version());
            when(rs.getString("halt_reason")).thenReturn(null);
            when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(s.createdAt()));
            return rs;
        }
    }
}
