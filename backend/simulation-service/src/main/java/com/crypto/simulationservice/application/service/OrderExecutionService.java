package com.crypto.simulationservice.application.service;

import com.crypto.financecore.finance.Decimal;
import com.crypto.simulationservice.infrastructure.streaming.SessionStreamRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

@Service
public class OrderExecutionService {

    private static final Logger log = LoggerFactory.getLogger(OrderExecutionService.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final SimulationMarketCatalog catalog;
    private final SimulationReportingService reportingService;
    private final SessionStreamRegistry streamRegistry;
    private final Clock clock;

    public OrderExecutionService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            SimulationMarketCatalog catalog,
            SimulationReportingService reportingService,
            SessionStreamRegistry streamRegistry,
            Clock clock
    ) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.catalog = catalog;
        this.reportingService = reportingService;
        this.streamRegistry = streamRegistry;
        this.clock = clock;
    }

    @Transactional
    public void materializeCommandIfPending(UUID commandId) {
        materializeCommandIfPending(commandId, catalog::fetchLiveMarkPriceIfAvailable);
    }

    @Transactional
    public void materializeCommandIfPending(
            UUID commandId,
            Function<UUID, Optional<BigDecimal>> markPriceProvider
    ) {
        var cmdRows = jdbc.query("""
                SELECT c.id, c.session_id, c.type, c.status, c.payload::text AS payload_json, s.status AS session_status
                FROM commands c
                JOIN simulation_sessions s ON s.id = c.session_id
                WHERE c.id = ?
                FOR UPDATE OF c
                """,
                (rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getObject("id", UUID.class));
                    m.put("sessionId", rs.getObject("session_id", UUID.class));
                    m.put("type", rs.getString("type"));
                    m.put("status", rs.getString("status"));
                    m.put("payloadJson", rs.getString("payload_json"));
                    m.put("sessionStatus", rs.getString("session_status"));
                    return m;
                },
                commandId
        );
        if (cmdRows.isEmpty()) {
            return;
        }
        var cmd = cmdRows.getFirst();
        if (!"ACCEPTED".equals(cmd.get("status")) || !"SUBMIT".equals(cmd.get("type"))) {
            return;
        }

        UUID sessionId = (UUID) cmd.get("sessionId");
        Instant now = clock.instant();

        if (!"RUNNING".equals(cmd.get("sessionStatus"))) {
            rejectCommand(commandId, sessionId, "SESSION_HALTED", now);
            return;
        }

        JsonNode payload;
        try {
            payload = objectMapper.readTree((String) cmd.get("payloadJson"));
        } catch (Exception ex) {
            log.warn("Invalid command payload json for commandId={}", commandId);
            rejectCommand(commandId, sessionId, "INVALID_SUBMIT", now);
            return;
        }

        UUID planId = UUID.fromString(payload.get("planId").asText());
        int revision = payload.path("revision").asInt(1);

        var jsonList = jdbc.queryForList(
                "SELECT canonical_json::text FROM execution_plans WHERE plan_id = ? AND revision = ?",
                String.class,
                planId,
                revision
        );
        if (jsonList.isEmpty()) {
            rejectCommand(commandId, sessionId, "NOT_FOUND", now);
            return;
        }

        JsonNode plan;
        try {
            plan = objectMapper.readTree(jsonList.getFirst());
        } catch (Exception ex) {
            log.warn("Invalid execution plan json for planId={} revision={}", planId, revision);
            rejectCommand(commandId, sessionId, "INVALID_SUBMIT", now);
            return;
        }

        UUID instrumentId = UUID.fromString(plan.get("instrumentId").asText());
        String side = plan.path("side").asText("LONG");
        String orderType = plan.path("orderType").asText("MARKET");
        BigDecimal quantity = parseNullableDecimal(plan.path("quantity"));
        BigDecimal referenceEntry = parseNullableDecimal(plan.path("referenceEntry"));
        BigDecimal marginRequired = parseNullableDecimal(plan.path("marginRequired"));
        BigDecimal openFee = parseNullableDecimal(plan.path("openFeeEstimate"));
        if (quantity == null || referenceEntry == null || marginRequired == null || openFee == null) {
            rejectCommand(commandId, sessionId, "INVALID_SUBMIT", now);
            return;
        }
        BigDecimal limitPrice = parseNullableDecimal(plan.path("limitPrice"));
        BigDecimal stopLoss = parseNullableDecimal(plan.path("stopLoss"));
        BigDecimal takeProfit = parseNullableDecimal(plan.path("takeProfit"));
        int horizonSeconds = plan.path("horizonSeconds").asInt(3600);
        String expiryPolicy = plan.path("expiryPolicy").asText("CLOSE_AT_DEADLINE");
        long generation = plan.path("positionGeneration").asLong(1L);

        boolean fillImmediately = "MARKET".equals(orderType);
        BigDecimal entryPrice = referenceEntry;
        if ("LIMIT".equals(orderType) && limitPrice != null) {
            Optional<BigDecimal> liveMark = markPriceProvider.apply(instrumentId);
            if (liveMark.isPresent() && isLimitTriggerHit(side, liveMark.get(), limitPrice)) {
                fillImmediately = true;
                entryPrice = limitPrice;
            }
        }

        if (fillImmediately) {
            reportingService.closeOpenPositions(sessionId, instrumentId, null, now);
        }

        BigDecimal totalDeduct = marginRequired.add(openFee);
        BigDecimal available = lockAvailableBalance(sessionId);
        if (available == null || available.compareTo(totalDeduct) < 0) {
            rejectCommand(commandId, sessionId, "INSUFFICIENT_AVAILABLE_BALANCE", now);
            return;
        }

        jdbc.update("""
                UPDATE wallet_accounts
                SET balance = balance - ?
                WHERE session_id = ? AND kind = 'AVAILABLE'
                """, totalDeduct, sessionId);

        UUID orderId = UUID.randomUUID();
        if (fillImmediately) {
            UUID positionId = openPosition(
                    sessionId, instrumentId, side, quantity, entryPrice, marginRequired,
                    generation, stopLoss, takeProfit, horizonSeconds, expiryPolicy, now
            );

            jdbc.update("""
                    INSERT INTO orders(
                        id, session_id, instrument_id, side, type, q, status,
                        reserved_amount, position_id, reduce_only, limit_price,
                        reason, version, command_id, created_at, updated_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, 'FILLED', 0, ?, false, ?, NULL, 1, ?, ?, ?)
                    ON CONFLICT (command_id) DO NOTHING
                    """,
                    orderId, sessionId, instrumentId, side, orderType, quantity,
                    positionId, limitPrice, commandId, Timestamp.from(now), Timestamp.from(now)
            );

            jdbc.update("UPDATE commands SET status = 'FILLED', reason = NULL, updated_at = ? WHERE id = ?",
                    Timestamp.from(now), commandId);

            publishSessionUpdate(sessionId, commandId, "FILLED", orderId, positionId);
        } else {
            jdbc.update("""
                    INSERT INTO orders(
                        id, session_id, instrument_id, side, type, q, status,
                        reserved_amount, position_id, reduce_only, limit_price,
                        reason, version, command_id, created_at, updated_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, 'NEW', ?, NULL, false, ?, NULL, 1, ?, ?, ?)
                    ON CONFLICT (command_id) DO NOTHING
                    """,
                    orderId, sessionId, instrumentId, side, orderType, quantity,
                    totalDeduct, limitPrice, commandId, Timestamp.from(now), Timestamp.from(now)
            );

            jdbc.update("UPDATE commands SET status = 'NEW', reason = NULL, updated_at = ? WHERE id = ?",
                    Timestamp.from(now), commandId);

            publishSessionUpdate(sessionId, commandId, "NEW", orderId, null);
        }
    }

    @Transactional
    public int processPendingCommands(UUID sessionIdFilter) {
        List<UUID> pendingIds = sessionIdFilter != null
                ? jdbc.queryForList("""
                        SELECT id FROM commands
                        WHERE session_id = ? AND status = 'ACCEPTED' AND type = 'SUBMIT'
                        ORDER BY created_at ASC
                        LIMIT 50
                        """, UUID.class, sessionIdFilter)
                : jdbc.queryForList("""
                        SELECT id FROM commands
                        WHERE status = 'ACCEPTED' AND type = 'SUBMIT'
                        ORDER BY created_at ASC
                        LIMIT 50
                        """, UUID.class);

        int count = 0;
        for (UUID commandId : pendingIds) {
            materializeCommandIfPending(commandId);
            count++;
        }
        return count;
    }

    @Transactional
    public int matchOpenLimitOrders(UUID sessionIdFilter) {
        return matchOpenLimitOrders(sessionIdFilter, catalog::fetchLiveMarkPriceIfAvailable);
    }

    @Transactional
    public int matchOpenLimitOrders(
            UUID sessionIdFilter,
            Function<UUID, Optional<BigDecimal>> markPriceProvider
    ) {
        List<Map<String, Object>> candidates = sessionIdFilter != null
                ? jdbc.query("""
                        SELECT o.id, o.instrument_id, o.side, o.limit_price
                        FROM orders o
                        JOIN simulation_sessions s ON s.id = o.session_id
                        WHERE o.session_id = ? AND o.status = 'NEW' AND o.type = 'LIMIT'
                          AND o.limit_price IS NOT NULL AND s.status = 'RUNNING'
                        ORDER BY o.created_at ASC
                        LIMIT 100
                        """,
                        (rs, n) -> Map.of(
                                "id", rs.getObject("id", UUID.class),
                                "instrumentId", rs.getObject("instrument_id", UUID.class),
                                "side", rs.getString("side"),
                                "limitPrice", rs.getBigDecimal("limit_price")
                        ),
                        sessionIdFilter)
                : jdbc.query("""
                        SELECT o.id, o.instrument_id, o.side, o.limit_price
                        FROM orders o
                        JOIN simulation_sessions s ON s.id = o.session_id
                        WHERE o.status = 'NEW' AND o.type = 'LIMIT'
                          AND o.limit_price IS NOT NULL AND s.status = 'RUNNING'
                        ORDER BY o.created_at ASC
                        LIMIT 100
                        """,
                        (rs, n) -> Map.of(
                                "id", rs.getObject("id", UUID.class),
                                "instrumentId", rs.getObject("instrument_id", UUID.class),
                                "side", rs.getString("side"),
                                "limitPrice", rs.getBigDecimal("limit_price")
                        ));

        if (candidates.isEmpty()) {
            return 0;
        }

        Map<UUID, Optional<BigDecimal>> markCache = new HashMap<>();
        int filled = 0;
        for (Map<String, Object> candidate : candidates) {
            UUID instrumentId = (UUID) candidate.get("instrumentId");
            String side = (String) candidate.get("side");
            BigDecimal limitPrice = (BigDecimal) candidate.get("limitPrice");
            Optional<BigDecimal> liveMark = markCache.computeIfAbsent(
                    instrumentId, markPriceProvider
            );
            if (liveMark.isPresent() && isLimitTriggerHit(side, liveMark.get(), limitPrice)) {
                if (fillOpenLimitOrder((UUID) candidate.get("id"))) {
                    filled++;
                }
            }
        }
        return filled;
    }

    public static boolean isLimitTriggerHit(String side, BigDecimal markPrice, BigDecimal limitPrice) {
        if (markPrice == null || limitPrice == null) {
            return false;
        }
        if ("LONG".equals(side)) {
            return markPrice.compareTo(limitPrice) <= 0;
        }
        if ("SHORT".equals(side)) {
            return markPrice.compareTo(limitPrice) >= 0;
        }
        return false;
    }

    public Optional<JsonNode> loadPlanForCommand(UUID commandId) {
        if (commandId == null) {
            return Optional.empty();
        }
        var payloadList = jdbc.queryForList(
                "SELECT payload::text FROM commands WHERE id = ?",
                String.class,
                commandId
        );
        if (payloadList.isEmpty() || payloadList.getFirst() == null) {
            return Optional.empty();
        }
        try {
            JsonNode payload = objectMapper.readTree(payloadList.getFirst());
            if (!payload.hasNonNull("planId")) {
                return Optional.empty();
            }
            UUID planId = UUID.fromString(payload.get("planId").asText());
            int revision = payload.path("revision").asInt(1);
            var planJsonList = jdbc.queryForList(
                    "SELECT canonical_json::text FROM execution_plans WHERE plan_id = ? AND revision = ?",
                    String.class,
                    planId,
                    revision
            );
            if (planJsonList.isEmpty() || planJsonList.getFirst() == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readTree(planJsonList.getFirst()));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private boolean fillOpenLimitOrder(UUID orderId) {
        var rows = jdbc.query("""
                SELECT o.id, o.session_id, o.instrument_id, o.side, o.q, o.limit_price,
                       o.reserved_amount, o.status, o.command_id, s.status AS session_status
                FROM orders o
                JOIN simulation_sessions s ON s.id = o.session_id
                WHERE o.id = ?
                FOR UPDATE OF o
                """,
                (rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("sessionId", rs.getObject("session_id", UUID.class));
                    m.put("instrumentId", rs.getObject("instrument_id", UUID.class));
                    m.put("side", rs.getString("side"));
                    m.put("q", rs.getBigDecimal("q"));
                    m.put("limitPrice", rs.getBigDecimal("limit_price"));
                    m.put("reservedAmount", rs.getBigDecimal("reserved_amount"));
                    m.put("status", rs.getString("status"));
                    m.put("commandId", rs.getObject("command_id", UUID.class));
                    m.put("sessionStatus", rs.getString("session_status"));
                    return m;
                },
                orderId
        );
        if (rows.isEmpty()) {
            return false;
        }
        var order = rows.getFirst();
        if (!"NEW".equals(order.get("status")) || !"RUNNING".equals(order.get("sessionStatus"))) {
            return false;
        }

        UUID sessionId = (UUID) order.get("sessionId");
        UUID instrumentId = (UUID) order.get("instrumentId");
        String side = (String) order.get("side");
        BigDecimal q = (BigDecimal) order.get("q");
        BigDecimal limitPrice = (BigDecimal) order.get("limitPrice");
        BigDecimal reserved = (BigDecimal) order.get("reservedAmount");
        UUID commandId = (UUID) order.get("commandId");
        if (limitPrice == null || reserved == null) {
            return false;
        }

        Optional<JsonNode> planOpt = loadPlanForCommand(commandId);
        BigDecimal marginRequired = planOpt.map(p -> parseNullableDecimal(p.path("marginRequired"))).orElse(reserved);
        if (marginRequired == null || marginRequired.signum() <= 0) {
            marginRequired = reserved;
        }
        BigDecimal openFee = planOpt.map(p -> parseNullableDecimal(p.path("openFeeEstimate"))).orElse(BigDecimal.ZERO);
        if (openFee == null) {
            openFee = BigDecimal.ZERO;
        }
        BigDecimal stopLoss = planOpt.map(p -> parseNullableDecimal(p.path("stopLoss"))).orElse(null);
        BigDecimal takeProfit = planOpt.map(p -> parseNullableDecimal(p.path("takeProfit"))).orElse(null);
        int horizonSeconds = planOpt.map(p -> p.path("horizonSeconds").asInt(3600)).orElse(3600);
        String expiryPolicy = planOpt.map(p -> p.path("expiryPolicy").asText("CLOSE_AT_DEADLINE"))
                .orElse("CLOSE_AT_DEADLINE");
        long generation = planOpt.map(p -> p.path("positionGeneration").asLong(1L)).orElse(1L);

        Instant now = clock.instant();
        reportingService.closeOpenPositions(sessionId, instrumentId, null, now);

        UUID positionId = openPosition(
                sessionId, instrumentId, side, q, limitPrice, marginRequired,
                generation, stopLoss, takeProfit, horizonSeconds, expiryPolicy, now
        );

        BigDecimal usedTotal = marginRequired.add(openFee);
        BigDecimal excess = reserved.subtract(usedTotal);
        if (excess.signum() > 0) {
            jdbc.update("""
                    UPDATE wallet_accounts
                    SET balance = balance + ?
                    WHERE session_id = ? AND kind = 'AVAILABLE'
                    """, excess, sessionId);
        }

        jdbc.update("""
                UPDATE orders
                SET status = 'FILLED', position_id = ?, reserved_amount = 0,
                    version = version + 1, updated_at = ?
                WHERE id = ?
                """, positionId, Timestamp.from(now), orderId);

        if (commandId != null) {
            jdbc.update("UPDATE commands SET status = 'FILLED', updated_at = ? WHERE id = ?",
                    Timestamp.from(now), commandId);
        }

        publishSessionUpdate(sessionId, commandId, "FILLED", orderId, positionId);
        return true;
    }

    private UUID openPosition(
            UUID sessionId,
            UUID instrumentId,
            String side,
            BigDecimal quantity,
            BigDecimal entryPrice,
            BigDecimal marginRequired,
            long generation,
            BigDecimal stopLoss,
            BigDecimal takeProfit,
            int horizonSeconds,
            String expiryPolicy,
            Instant now
    ) {
        UUID positionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO positions(
                    id, session_id, instrument_id, side, q, entry_price,
                    original_margin, initial_margin_remaining, state, generation,
                    version, stop_loss, take_profit, deadline_at, expiry_policy,
                    horizon_seconds, close_requested, opened_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?, 1, ?, ?, ?, ?, ?, false, ?)
                """,
                positionId,
                sessionId,
                instrumentId,
                side,
                quantity,
                entryPrice,
                marginRequired,
                marginRequired,
                generation,
                stopLoss,
                takeProfit,
                Timestamp.from(now.plusSeconds(horizonSeconds)),
                expiryPolicy,
                horizonSeconds,
                Timestamp.from(now)
        );
        return positionId;
    }

    private BigDecimal lockAvailableBalance(UUID sessionId) {
        var balances = jdbc.queryForList("""
                SELECT balance FROM wallet_accounts
                WHERE session_id = ? AND kind = 'AVAILABLE'
                FOR UPDATE
                """, BigDecimal.class, sessionId);
        return balances.isEmpty() ? null : balances.getFirst();
    }

    private void rejectCommand(UUID commandId, UUID sessionId, String reason, Instant now) {
        jdbc.update("UPDATE commands SET status = 'REJECTED', reason = ?, updated_at = ? WHERE id = ?",
                reason, Timestamp.from(now), commandId);
        publishSessionUpdate(sessionId, commandId, "REJECTED", null, null);
    }

    private void publishSessionUpdate(UUID sessionId, UUID commandId, String status, UUID orderId, UUID positionId) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sessionId", sessionId.toString());
            payload.put("status", status);
            if (commandId != null) {
                payload.put("commandId", commandId.toString());
            }
            if (orderId != null) {
                payload.put("orderId", orderId.toString());
            }
            if (positionId != null) {
                payload.put("positionId", positionId.toString());
            }
            streamRegistry.publish(sessionId, "session.updated", payload);
        } catch (Exception ex) {
            log.debug("SSE publish skipped for sessionId={}: {}", sessionId, ex.getMessage());
        }
    }

    private static BigDecimal parseNullableDecimal(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String text = node.asText("").trim();
        if (text.isBlank()) {
            return null;
        }
        BigDecimal value = new BigDecimal(text);
        return value.scale() > 18 ? value.setScale(18, RoundingMode.HALF_EVEN) : value;
    }
}
