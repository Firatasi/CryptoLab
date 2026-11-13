package com.crypto.simulationservice.api.controller;

import com.crypto.financecore.finance.Decimal;
import com.crypto.financecore.finance.Finance;
import com.crypto.financecore.finance.PositionSide;
import com.crypto.simulationservice.api.dto.request.AiPreviewRequest;
import com.crypto.simulationservice.api.dto.request.ManualPreviewRequest;
import com.crypto.simulationservice.application.service.AiPreviewService;
import com.crypto.simulationservice.application.service.CreateSession;
import com.crypto.simulationservice.application.service.LiquidationMonitorService;
import com.crypto.simulationservice.application.service.OrderExecutionService;
import com.crypto.simulationservice.application.service.PlanHash;
import com.crypto.simulationservice.application.service.SimulationMarketCatalog;
import com.crypto.simulationservice.application.service.SimulationReportingService;
import com.crypto.simulationservice.domain.exception.BusinessException;
import com.crypto.simulationservice.domain.model.ExecutionPlan;
import com.crypto.simulationservice.domain.service.ExecutionGate;
import com.crypto.simulationservice.domain.service.PreviewFactory;
import com.crypto.simulationservice.domain.service.RiskPolicyEvaluator;
import com.crypto.simulationservice.infrastructure.streaming.SessionStreamRegistry;
import com.crypto.simulationservice.infrastructure.streaming.SseRing;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/sim")
public class SessionController {

    private static final UUID DEFAULT_RULE_PROFILE_ID = UUID.fromString("88888888-8888-4888-8888-888888888888");
    private static final UUID DEFAULT_RISK_POLICY_ID = UUID.fromString("99999999-9999-4999-8999-999999999999");

    private final java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofMillis(1200))
            .build();

    private final CreateSession createSession;
    private final AiPreviewService aiPreviewService;
    private final SimulationMarketCatalog catalog;
    private final SimulationReportingService reportingService;
    private final LiquidationMonitorService liquidationMonitorService;
    private final OrderExecutionService orderExecutionService;
    private final SessionStreamRegistry streamRegistry;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public SessionController(
            CreateSession createSession,
            AiPreviewService aiPreviewService,
            SimulationMarketCatalog catalog,
            SimulationReportingService reportingService,
            LiquidationMonitorService liquidationMonitorService,
            OrderExecutionService orderExecutionService,
            SessionStreamRegistry streamRegistry,
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.createSession = createSession;
        this.aiPreviewService = aiPreviewService;
        this.catalog = catalog;
        this.reportingService = reportingService;
        this.liquidationMonitorService = liquidationMonitorService;
        this.orderExecutionService = orderExecutionService;
        this.streamRegistry = streamRegistry;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @GetMapping("/sessions")
    public Map<String, Object> listSessions(@AuthenticationPrincipal Jwt jwt) {
        UUID ownerId = UUID.fromString(jwt.getSubject());
        return Map.of("items", querySessionMaps(ownerId, null));
    }

    @PostMapping("/sessions")
    @Transactional
    public ResponseEntity<Map<String, Object>> create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody Map<String, Object> body
    ) {
        ensureMetadataTable();
        UUID ownerId = UUID.fromString(jwt.getSubject());
        String rawDeposit = body.get("initialDeposit") != null
                ? body.get("initialDeposit").toString().trim()
                : "10000";
        BigDecimal deposit = Decimal.parse(rawDeposit);

        String rawName = body.get("name") != null
                ? body.get("name").toString().trim()
                : "";

        UUID ruleProfileId = parseUuidOrDefault(body.get("ruleProfileId"), DEFAULT_RULE_PROFILE_ID);
        UUID riskPolicyId = parseUuidOrDefault(body.get("riskPolicyId"), DEFAULT_RISK_POLICY_ID);

        UUID sessionId = createSession.execute(ownerId, deposit, ruleProfileId, riskPolicyId);
        if (!rawName.isEmpty()) {
            jdbc.update("""
                    INSERT INTO session_metadata(session_id, name, deleted, updated_at)
                    VALUES (?, ?, false, ?)
                    ON CONFLICT (session_id) DO UPDATE SET name = EXCLUDED.name, updated_at = EXCLUDED.updated_at
                    """, sessionId, rawName, Timestamp.from(clock.instant()));
        }
        Map<String, Object> created = findSessionMap(ownerId, sessionId);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/sessions/{id}")
    @Transactional
    public Map<String, Object> getSession(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") String rawSessionId
    ) {
        UUID ownerId = UUID.fromString(jwt.getSubject());
        UUID sessionId = resolveOwnedSessionId(ownerId, rawSessionId);
        liquidationMonitorService.checkAndPublish(ownerId, sessionId);
        return findSessionMap(ownerId, sessionId);
    }

    @GetMapping("/reporting/snapshot")
    public SimulationReportingService.ReportingSnapshot reportingSnapshot(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "sessionId", required = false) UUID sessionId
    ) {
        UUID ownerId = UUID.fromString(jwt.getSubject());
        return reportingService.snapshotForOwner(ownerId, sessionId);
    }

    @PostMapping("/sessions/{id}/halt")
    @Transactional
    public Map<String, Object> haltSession(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") String rawSessionId
    ) {
        UUID ownerId = UUID.fromString(jwt.getSubject());
        UUID sessionId = resolveOwnedSessionId(ownerId, rawSessionId);
        int updated = jdbc.update("""
                UPDATE simulation_sessions
                SET status = 'HALTED', version = version + 1
                WHERE id = ? AND owner_id = ?
                """, sessionId, ownerId);
        if (updated == 0) {
            throw new BusinessException(BusinessException.Code.NOT_FOUND);
        }
        jdbc.update("""
                INSERT INTO halt_reasons(session_id, reason, created_at)
                VALUES (?, 'MANUAL_OPERATOR_HALT', ?)
                ON CONFLICT (session_id, reason) DO NOTHING
                """, sessionId, Timestamp.from(clock.instant()));

        Map<String, Object> view = findSessionMap(ownerId, sessionId);
        ringFor(sessionId).publish("session.updated", view);
        return view;
    }

    @PostMapping("/sessions/{id}/resume")
    @Transactional
    public Map<String, Object> resumeSession(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") String rawSessionId
    ) {
        UUID ownerId = UUID.fromString(jwt.getSubject());
        UUID sessionId = resolveOwnedSessionId(ownerId, rawSessionId);
        int updated = jdbc.update("""
                UPDATE simulation_sessions
                SET status = 'RUNNING', version = version + 1
                WHERE id = ? AND owner_id = ?
                """, sessionId, ownerId);
        if (updated == 0) {
            throw new BusinessException(BusinessException.Code.NOT_FOUND);
        }
        jdbc.update("DELETE FROM halt_reasons WHERE session_id = ?", sessionId);

        Map<String, Object> view = findSessionMap(ownerId, sessionId);
        ringFor(sessionId).publish("session.updated", view);
        return view;
    }

    @PostMapping("/sessions/{id}/close-all")
    @Transactional
    public Map<String, Object> closeAllPositions(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") String rawSessionId
    ) {
        UUID ownerId = UUID.fromString(jwt.getSubject());
        UUID sessionId = resolveOwnedSessionId(ownerId, rawSessionId);
        findSessionMap(ownerId, sessionId);

        int closedCount = reportingService.closeOpenPositions(sessionId, null, null, clock.instant());

        ringFor(sessionId).publish("session.updated", findSessionMap(ownerId, sessionId));
        return Map.of(
                "sessionId", sessionId.toString(),
                "closeRequestedCount", closedCount
        );
    }

    @PostMapping("/sessions/{id}/delete")
    @Transactional
    public Map<String, Object> deleteSession(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") String rawSessionId
    ) {
        ensureMetadataTable();
        UUID ownerId = UUID.fromString(jwt.getSubject());
        UUID sessionId = resolveOwnedSessionId(ownerId, rawSessionId);
        findSessionMap(ownerId, sessionId);
        Instant now = clock.instant();

        jdbc.update("""
                UPDATE positions
                SET state = 'CLOSED', close_requested = true, closed_at = ?, version = version + 1
                WHERE session_id = ? AND state IN ('OPEN', 'LIQUIDATING')
                """, Timestamp.from(now), sessionId);

        jdbc.update("""
                UPDATE orders
                SET status = 'CANCELLED', version = version + 1, updated_at = ?
                WHERE session_id = ? AND status = 'NEW'
                """, Timestamp.from(now), sessionId);

        jdbc.update("""
                UPDATE simulation_sessions
                SET status = 'COMPLETED', version = version + 1
                WHERE id = ? AND owner_id = ?
                """, sessionId, ownerId);

        jdbc.update("""
                INSERT INTO session_metadata(session_id, name, deleted, updated_at)
                VALUES (?, '', true, ?)
                ON CONFLICT (session_id) DO UPDATE SET deleted = true, updated_at = EXCLUDED.updated_at
                """, sessionId, Timestamp.from(now));

        return Map.of(
                "sessionId", sessionId.toString(),
                "deleted", true
        );
    }

    @GetMapping(value = "/sessions/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamSession(
            @PathVariable("id") String rawSessionId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId
    ) {
        UUID streamKey;
        try {
            streamKey = UUID.fromString(rawSessionId);
        } catch (Exception e) {
            streamKey = UUID.nameUUIDFromBytes(rawSessionId.getBytes(StandardCharsets.UTF_8));
        }
        return ringFor(streamKey).subscribe(lastEventId);
    }

    @PostMapping("/order-previews")
    @Transactional
    public Map<String, Object> previewOrder(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody Map<String, Object> rawBody
    ) throws Exception {
        JsonNode body = objectMapper.valueToTree(rawBody);
        UUID ownerId = UUID.fromString(jwt.getSubject());
        String source = body.path("source").asText("MANUAL");

        UUID sessionId = resolveOwnedSessionId(ownerId, body.path("sessionId").asText(""));
        Map<String, Object> session = findSessionMap(ownerId, sessionId);
        if (!"RUNNING".equals(session.get("status"))) {
            throw new BusinessException(BusinessException.Code.SESSION_HALTED);
        }

        BigDecimal available = parseDerivedDecimal(session.get("availableBalance").toString());
        BigDecimal equity = parseDerivedDecimal(session.get("equity").toString());
        UUID ruleProfileId = UUID.fromString(session.get("ruleProfileId").toString());
        UUID riskPolicyId = UUID.fromString(session.get("riskPolicyId").toString());

        UUID instrumentId = catalog.resolveInstrumentUuid(
                body.hasNonNull("instrumentId") ? body.get("instrumentId").asText() : "BTCUSDT"
        );

        com.fasterxml.jackson.databind.node.ObjectNode normalizedBody = body.deepCopy();
        normalizedBody.put("sessionId", sessionId.toString());
        if ("AI_PROPOSAL".equals(source)) {
            normalizedBody.remove("instrumentId");
        } else {
            normalizedBody.put("instrumentId", instrumentId.toString());
        }

        BigDecimal refPrice = resolveReferencePrice(instrumentId, normalizedBody);
        BigDecimal tickSize = inferTickSize(refPrice);
        BigDecimal stepSize = inferStepSize(refPrice);
        String orderType = normalizedBody.path("orderType").asText("MARKET");
        BigDecimal feeRate = SimulationMarketCatalog.resolveTakerFeeRate(ruleProfileId, orderType);
        RiskPolicyEvaluator.Limits limits = resolveRiskLimits(riskPolicyId, equity);

        var rule = new PreviewFactory.Rule(
                ruleProfileId,
                tickSize,
                stepSize,
                new BigDecimal("5"),
                feeRate,
                SimulationReportingService.DEFAULT_MAINTENANCE_TIERS
        );

        var context = new PreviewFactory.Context(
                refPrice.subtract(tickSize),
                refPrice.add(tickSize),
                available,
                new BigDecimal("5"),
                1L,
                60L,
                refPrice.multiply(new BigDecimal("0.90")),
                refPrice.multiply(new BigDecimal("1.10")),
                available,
                false,
                false
        );

        if ("AI_PROPOSAL".equals(source)) {
            AiPreviewRequest aiReq = objectMapper.treeToValue(normalizedBody, AiPreviewRequest.class);
            try {
                return toPlanMap(aiPreviewService.previewAiProposal(
                        ownerId,
                        aiReq,
                        equity,
                        BigDecimal.ZERO,
                        new BigDecimal("0.001"),
                        limits,
                        context,
                        rule
                ));
            } catch (BusinessException ex) {
                return toPlanMap(previewAiProposalFromStrategyService(
                        ownerId,
                        jwt.getTokenValue(),
                        sessionId,
                        aiReq,
                        equity,
                        limits,
                        rule
                ));
            }
        }

        ManualPreviewRequest manualReq = objectMapper.treeToValue(normalizedBody, ManualPreviewRequest.class);
        BigDecimal requestedLeverage = Decimal.parse(manualReq.leverage());
        if (requestedLeverage.compareTo(BigDecimal.ONE) < 0
                || requestedLeverage.compareTo(limits.maxLeverage()) > 0) {
            throw new IllegalArgumentException("INVALID_LEVERAGE");
        }
        if (manualReq.originProposalId() != null) {
            return toPlanMap(aiPreviewService.previewModifiedProposal(ownerId, manualReq, context, rule));
        }

        long gen = ensureGeneration(sessionId, instrumentId);
        Instant now = clock.instant();
        var req = new PreviewFactory.Request(
                sessionId,
                instrumentId,
                1L,
                gen,
                manualReq.side(),
                manualReq.orderType(),
                Decimal.parse(manualReq.marginBudget()),
                requestedLeverage,
                manualReq.limitPrice() != null && !manualReq.limitPrice().isBlank() ? Decimal.parse(manualReq.limitPrice()) : null,
                manualReq.stopLoss() != null && !manualReq.stopLoss().isBlank() ? Decimal.parse(manualReq.stopLoss()) : null,
                manualReq.takeProfit() != null && !manualReq.takeProfit().isBlank() ? Decimal.parse(manualReq.takeProfit()) : null,
                manualReq.horizonSeconds(),
                manualReq.expiryPolicy()
        );

        ExecutionPlan unsigned = PreviewFactory.create(req, context, rule, now);
        String canonicalJson = new String(PlanHash.canonical(unsigned), StandardCharsets.UTF_8);
        String hash = PlanHash.hash(unsigned);

        ExecutionPlan signed = new ExecutionPlan(
                unsigned.planId(),
                unsigned.revision(),
                hash,
                unsigned.sessionId(),
                unsigned.instrumentId(),
                unsigned.instrumentVersion(),
                unsigned.source(),
                unsigned.proposalId(),
                unsigned.positionGeneration(),
                unsigned.side(),
                unsigned.orderType(),
                unsigned.quantity(),
                unsigned.referenceEntry(),
                unsigned.limitPrice(),
                unsigned.marginRequired(),
                unsigned.openFeeEstimate(),
                unsigned.notional(),
                unsigned.leverage(),
                unsigned.stopLoss(),
                unsigned.takeProfit(),
                unsigned.horizonSeconds(),
                unsigned.expiryPolicy(),
                unsigned.priceLowerBound(),
                unsigned.priceUpperBound(),
                unsigned.maxOpenFee(),
                unsigned.maxSlippageBps(),
                unsigned.ruleProfileId(),
                unsigned.riskPolicyVersion(),
                unsigned.createdAt(),
                unsigned.validUntil(),
                unsigned.estimatedLiquidationPrice(),
                unsigned.warnings()
        );

        jdbc.update("""
                INSERT INTO execution_plans(
                    plan_id,
                    revision,
                    session_id,
                    instrument_id,
                    canonical_json,
                    hash,
                    position_generation,
                    valid_until,
                    state
                )
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, 'PREVIEWED')
                """,
                signed.planId(),
                signed.revision(),
                signed.sessionId(),
                signed.instrumentId(),
                canonicalJson,
                signed.planHash(),
                signed.positionGeneration(),
                Timestamp.from(signed.validUntil())
        );

        return toPlanMap(signed);
    }

    @GetMapping("/commands/{commandId}")
    @Transactional
    public Map<String, Object> getCommandStatus(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("commandId") UUID commandId
    ) throws Exception {
        UUID ownerId = UUID.fromString(jwt.getSubject());
        var rows = jdbc.query("""
                SELECT c.id, c.session_id, c.type, c.payload::text AS payload_json, c.status, c.reason, c.updated_at
                FROM commands c
                JOIN simulation_sessions s ON s.id = c.session_id
                WHERE c.id = ? AND s.owner_id = ?
                """,
                (rs, n) -> Map.of(
                        "id", rs.getObject("id", UUID.class),
                        "sessionId", rs.getObject("session_id", UUID.class),
                        "type", rs.getString("type"),
                        "payloadJson", rs.getString("payload_json"),
                        "status", rs.getString("status"),
                        "reason", rs.getString("reason") != null ? rs.getString("reason") : "",
                        "updatedAt", rs.getTimestamp("updated_at").toInstant()
                ),
                commandId, ownerId
        );

        if (rows.isEmpty()) {
            throw new BusinessException(BusinessException.Code.NOT_FOUND);
        }

        var cmd = rows.getFirst();
        UUID sessionId = (UUID) cmd.get("sessionId");
        String status = (String) cmd.get("status");

        // ACCEPTED komutu ilk sorgulandığında pozisyonu / emri oluşturup FILLED/NEW durumuna geçir
        if ("ACCEPTED".equals(status) && "SUBMIT".equals(cmd.get("type"))) {
            orderExecutionService.materializeCommandIfPending(commandId);
        }
        orderExecutionService.matchOpenLimitOrders(sessionId);

        var refreshedCmdRows = jdbc.queryForList(
                "SELECT status, reason, updated_at FROM commands WHERE id = ?",
                commandId
        );
        String latestStatus = refreshedCmdRows.isEmpty()
                ? status
                : String.valueOf(refreshedCmdRows.getFirst().get("status"));
        Object rawReason = refreshedCmdRows.isEmpty() ? cmd.get("reason") : refreshedCmdRows.getFirst().get("reason");
        String latestReason = rawReason != null ? rawReason.toString() : "";
        Instant latestUpdatedAt = refreshedCmdRows.isEmpty()
                ? (Instant) cmd.get("updatedAt")
                : ((Timestamp) refreshedCmdRows.getFirst().get("updated_at")).toInstant();

        var orderRows = jdbc.query("""
                SELECT id, position_id, status, q, limit_price, updated_at
                FROM orders
                WHERE command_id = ?
                """,
                (rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("orderId", rs.getObject("id", UUID.class));
                    m.put("positionId", rs.getObject("position_id", UUID.class));
                    m.put("status", rs.getString("status"));
                    m.put("q", rs.getBigDecimal("q"));
                    m.put("limitPrice", rs.getBigDecimal("limit_price"));
                    m.put("updatedAt", rs.getTimestamp("updated_at").toInstant());
                    return m;
                },
                commandId
        );

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("commandId", commandId.toString());
        result.put("sessionId", sessionId.toString());
        if (!orderRows.isEmpty()) {
            var ord = orderRows.getFirst();
            UUID orderId = (UUID) ord.get("orderId");
            UUID posId = (UUID) ord.get("positionId");
            result.put("orderId", orderId != null ? orderId.toString() : null);
            result.put("positionId", posId != null ? posId.toString() : null);
            result.put("status", ord.get("status"));
            result.put("filledQuantity", Decimal.wire((BigDecimal) ord.get("q")));
            if (posId != null) {
                var entryPrices = jdbc.queryForList(
                        "SELECT entry_price FROM positions WHERE id = ?",
                        BigDecimal.class,
                        posId
                );
                if (!entryPrices.isEmpty() && entryPrices.getFirst() != null) {
                    result.put("fillPrice", Decimal.wire(entryPrices.getFirst()));
                }
            }
            if (!result.containsKey("fillPrice") && ord.get("limitPrice") != null) {
                result.put("fillPrice", Decimal.wire((BigDecimal) ord.get("limitPrice")));
            }
            result.put("updatedAt", ord.get("updatedAt").toString());
        } else {
            result.put("status", "CLOSE_POSITION".equals(cmd.get("type")) ? "FILLED" : latestStatus);
            if (!latestReason.isBlank()) {
                result.put("reason", latestReason);
            }
            result.put("updatedAt", latestUpdatedAt.toString());
        }
        return result;
    }

    @GetMapping("/positions")
    @Transactional
    public Map<String, Object> listPositions(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam("sessionId") String rawSessionId
    ) {
        UUID ownerId = UUID.fromString(jwt.getSubject());
        UUID sessionId = resolveOwnedSessionId(ownerId, rawSessionId);
        findSessionMap(ownerId, sessionId);
        orderExecutionService.processPendingCommands(sessionId);
        orderExecutionService.matchOpenLimitOrders(sessionId);
        liquidationMonitorService.checkAndPublish(ownerId, sessionId);

        List<Map<String, Object>> items = jdbc.query("""
                SELECT
                    id, session_id, instrument_id, side, q, entry_price,
                    original_margin, initial_margin_remaining, state, generation,
                    version, stop_loss, take_profit, deadline_at, expiry_policy,
                    horizon_seconds, close_requested, opened_at
                FROM positions
                WHERE session_id = ? AND state IN ('OPEN', 'LIQUIDATING')
                ORDER BY opened_at DESC
                """,
                (rs, n) -> mapPositionRow(rs),
                sessionId
        );
        return Map.of("items", items);
    }

    @PostMapping("/positions/{positionId}/close")
    @Transactional
    public ResponseEntity<Map<String, Object>> closePosition(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("positionId") UUID positionId
    ) {
        UUID ownerId = UUID.fromString(jwt.getSubject());
        var posList = jdbc.queryForList("""
                SELECT p.session_id
                FROM positions p
                JOIN simulation_sessions s ON s.id = p.session_id
                WHERE p.id = ? AND s.owner_id = ?
                """,
                UUID.class,
                positionId, ownerId
        );
        if (posList.isEmpty()) {
            throw new BusinessException(BusinessException.Code.NOT_FOUND);
        }
        UUID sessionId = posList.getFirst();
        Instant now = clock.instant();

        reportingService.closeOpenPositions(sessionId, null, positionId, now);

        UUID commandId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO commands(id, session_id, type, payload, status, reason, created_at, updated_at)
                VALUES (?, ?, 'CLOSE_POSITION', '{}'::jsonb, 'FILLED', NULL, ?, ?)
                """, commandId, sessionId, Timestamp.from(now), Timestamp.from(now));

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "commandId", commandId.toString(),
                "status", "ACCEPTED",
                "statusUrl", "/api/v1/sim/commands/" + commandId,
                "acceptedAt", now.toString()
        ));
    }

    @PatchMapping("/positions/{positionId}/brackets")
    @Transactional
    public Map<String, Object> updateBrackets(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("positionId") UUID positionId,
            @RequestBody Map<String, Object> body
    ) {
        UUID ownerId = UUID.fromString(jwt.getSubject());
        BigDecimal stopLoss = body.get("stopLoss") != null && !body.get("stopLoss").toString().isBlank()
                ? Decimal.parse(body.get("stopLoss").toString())
                : null;
        BigDecimal takeProfit = body.get("takeProfit") != null && !body.get("takeProfit").toString().isBlank()
                ? Decimal.parse(body.get("takeProfit").toString())
                : null;

        List<Map<String, Object>> currentRows = jdbc.query("""
                SELECT p.instrument_id, p.side, p.q, p.entry_price, p.state,
                       COALESCE(NULLIF(p.initial_margin_remaining, 0), p.original_margin) AS eff_margin
                FROM positions p
                JOIN simulation_sessions s ON s.id = p.session_id
                WHERE p.id = ? AND s.owner_id = ?
                FOR UPDATE OF p
                """,
                (rs, n) -> Map.of(
                        "instrumentId", rs.getObject("instrument_id", UUID.class),
                        "side", rs.getString("side"),
                        "q", rs.getBigDecimal("q"),
                        "entryPrice", rs.getBigDecimal("entry_price"),
                        "state", rs.getString("state"),
                        "margin", rs.getBigDecimal("eff_margin")
                ),
                positionId, ownerId);

        if (currentRows.isEmpty()) {
            throw new BusinessException(BusinessException.Code.NOT_FOUND);
        }

        Map<String, Object> current = currentRows.getFirst();
        String state = (String) current.get("state");
        if (!"OPEN".equals(state) && !"LIQUIDATING".equals(state)) {
            throw new BusinessException(BusinessException.Code.VERSION_CONFLICT);
        }

        UUID instrumentId = (UUID) current.get("instrumentId");
        String side = (String) current.get("side");
        BigDecimal q = (BigDecimal) current.get("q");
        BigDecimal entryPrice = (BigDecimal) current.get("entryPrice");
        BigDecimal margin = (BigDecimal) current.get("margin");
        BigDecimal markPrice = catalog.fetchLiveMarkPrice(instrumentId, entryPrice);
        ExecutionGate.bracket(side, markPrice, stopLoss, takeProfit, false, false);
        BigDecimal estimatedLiq = Finance.liquidation(
                PositionSide.valueOf(side),
                q,
                entryPrice,
                margin,
                SimulationReportingService.DEFAULT_MAINTENANCE_TIERS
        ).orElse(null);
        ExecutionGate.stopBeforeLiquidation(side, stopLoss, estimatedLiq);

        int updated = jdbc.update("""
                UPDATE positions p
                SET stop_loss = ?, take_profit = ?, version = p.version + 1
                FROM simulation_sessions s
                WHERE p.id = ? AND p.session_id = s.id AND s.owner_id = ?
                  AND p.state IN ('OPEN', 'LIQUIDATING')
                """, stopLoss, takeProfit, positionId, ownerId);

        if (updated == 0) {
            throw new BusinessException(BusinessException.Code.VERSION_CONFLICT);
        }

        return jdbc.queryForObject("""
                SELECT
                    id, session_id, instrument_id, side, q, entry_price,
                    original_margin, initial_margin_remaining, state, generation,
                    version, stop_loss, take_profit, deadline_at, expiry_policy,
                    horizon_seconds, close_requested, opened_at
                FROM positions
                WHERE id = ?
                """,
                (rs, n) -> mapPositionRow(rs),
                positionId
        );
    }

    @GetMapping("/orders")
    @Transactional
    public Map<String, Object> listOpenOrders(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam("sessionId") String rawSessionId
    ) {
        UUID ownerId = UUID.fromString(jwt.getSubject());
        UUID sessionId = resolveOwnedSessionId(ownerId, rawSessionId);
        findSessionMap(ownerId, sessionId);
        orderExecutionService.processPendingCommands(sessionId);
        orderExecutionService.matchOpenLimitOrders(sessionId);

        List<Map<String, Object>> items = jdbc.query("""
                SELECT
                    id, session_id, instrument_id, side, type, q,
                    limit_price, reserved_amount, status, version, command_id, created_at
                FROM orders
                WHERE session_id = ? AND status IN ('NEW')
                ORDER BY created_at DESC
                """,
                (rs, n) -> {
                    UUID instrumentId = rs.getObject("instrument_id", UUID.class);
                    UUID commandId = rs.getObject("command_id", UUID.class);
                    var planOpt = orderExecutionService.loadPlanForCommand(commandId);
                    String leverageStr = planOpt
                            .map(p -> p.path("leverage").asText(""))
                            .filter(s -> !s.isBlank())
                            .orElse("10");
                    String stopLossStr = planOpt
                            .map(p -> p.path("stopLoss").isNull() ? null : p.path("stopLoss").asText(null))
                            .filter(s -> s != null && !s.isBlank())
                            .orElse(null);
                    String takeProfitStr = planOpt
                            .map(p -> p.path("takeProfit").isNull() ? null : p.path("takeProfit").asText(null))
                            .filter(s -> s != null && !s.isBlank())
                            .orElse(null);

                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("orderId", rs.getObject("id", UUID.class).toString());
                    m.put("sessionId", rs.getObject("session_id", UUID.class).toString());
                    m.put("instrumentId", instrumentId.toString());
                    m.put("symbol", catalog.resolveSymbol(instrumentId));
                    m.put("side", rs.getString("side"));
                    m.put("orderType", rs.getString("type"));
                    m.put("quantity", Decimal.wire(rs.getBigDecimal("q")));
                    m.put("limitPrice", rs.getBigDecimal("limit_price") != null ? Decimal.wire(rs.getBigDecimal("limit_price")) : null);
                    m.put("marginReserved", Decimal.wire(rs.getBigDecimal("reserved_amount")));
                    m.put("leverage", leverageStr);
                    m.put("stopLoss", stopLossStr);
                    m.put("takeProfit", takeProfitStr);
                    m.put("status", rs.getString("status"));
                    m.put("version", rs.getLong("version"));
                    m.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
                    return m;
                },
                sessionId
        );
        return Map.of("items", items);
    }

    @PostMapping("/orders/{orderId}/cancel")
    @Transactional
    public Map<String, Object> cancelOrder(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("orderId") UUID orderId
    ) {
        UUID ownerId = UUID.fromString(jwt.getSubject());
        var rows = jdbc.query("""
                SELECT o.session_id, o.reserved_amount
                FROM orders o
                JOIN simulation_sessions s ON s.id = o.session_id
                WHERE o.id = ? AND s.owner_id = ? AND o.status = 'NEW'
                """,
                (rs, n) -> Map.of(
                        "sessionId", rs.getObject("session_id", UUID.class),
                        "reserved", rs.getBigDecimal("reserved_amount")
                ),
                orderId, ownerId
        );
        if (rows.isEmpty()) {
            throw new BusinessException(BusinessException.Code.NOT_FOUND);
        }
        UUID sessionId = (UUID) rows.getFirst().get("sessionId");
        BigDecimal reserved = (BigDecimal) rows.getFirst().get("reserved");

        jdbc.update("""
                UPDATE orders
                SET status = 'CANCELLED', version = version + 1, updated_at = ?
                WHERE id = ?
                """, Timestamp.from(clock.instant()), orderId);

        if (reserved != null && reserved.signum() > 0) {
            jdbc.update("""
                    UPDATE wallet_accounts
                    SET balance = balance + ?
                    WHERE session_id = ? AND kind = 'AVAILABLE'
                    """, reserved, sessionId);
        }

        return Map.of(
                "orderId", orderId.toString(),
                "status", "CANCELLED"
        );
    }

    private Map<String, Object> mapPositionRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        UUID instrumentId = rs.getObject("instrument_id", UUID.class);
        String side = rs.getString("side");
        BigDecimal q = rs.getBigDecimal("q");
        BigDecimal entry = rs.getBigDecimal("entry_price");
        BigDecimal margin = rs.getBigDecimal("initial_margin_remaining");
        BigDecimal mark = catalog.fetchLiveMarkPrice(instrumentId, entry);
        BigDecimal notional = q.multiply(entry);
        BigDecimal leverage = margin.signum() > 0
                ? notional.divide(margin, 2, java.math.RoundingMode.HALF_UP)
                : BigDecimal.TEN;
        BigDecimal pnl = "LONG".equals(side)
                ? mark.subtract(entry).multiply(q)
                : entry.subtract(mark).multiply(q);

        BigDecimal liq = Finance.liquidation(
                PositionSide.valueOf(side),
                q,
                entry,
                margin,
                SimulationReportingService.DEFAULT_MAINTENANCE_TIERS
        ).orElse(null);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("positionId", rs.getObject("id", UUID.class).toString());
        m.put("sessionId", rs.getObject("session_id", UUID.class).toString());
        m.put("instrumentId", instrumentId.toString());
        m.put("symbol", catalog.resolveSymbol(instrumentId));
        m.put("side", side);
        m.put("status", rs.getString("state"));
        m.put("quantity", Decimal.wire(q));
        m.put("entryPrice", Decimal.wire(entry));
        m.put("markPrice", Decimal.wire(mark));
        m.put("markAsOf", clock.instant().toString());
        m.put("isolatedMargin", Decimal.wire(margin));
        m.put("maintenanceMargin", Decimal.wire(notional.multiply(new BigDecimal("0.005"))));
        m.put("unrealizedPnl", Decimal.wire(pnl));
        m.put("estimatedLiquidationPrice", liq != null ? Decimal.wire(liq) : null);
        m.put("leverage", Decimal.wire(leverage));
        m.put("stopLoss", rs.getBigDecimal("stop_loss") != null ? Decimal.wire(rs.getBigDecimal("stop_loss")) : null);
        m.put("takeProfit", rs.getBigDecimal("take_profit") != null ? Decimal.wire(rs.getBigDecimal("take_profit")) : null);
        m.put("expiryPolicy", rs.getString("expiry_policy"));
        m.put("deadlineAt", rs.getTimestamp("deadline_at") != null ? rs.getTimestamp("deadline_at").toInstant().toString() : null);
        m.put("dataQuality", "FRESH");
        m.put("closeRequested", rs.getBoolean("close_requested"));
        m.put("version", rs.getLong("version"));
        m.put("openedAt", rs.getTimestamp("opened_at").toInstant().toString());
        return m;
    }

    private Map<String, Object> findSessionMap(UUID ownerId, UUID sessionId) {
        List<Map<String, Object>> list = querySessionMaps(ownerId, sessionId);
        if (list.isEmpty()) {
            throw new BusinessException(BusinessException.Code.NOT_FOUND);
        }
        return list.getFirst();
    }

    /**
     * Cüzdan listesi ve tekil oturum görünümü için başlangıç sermayesi, kullanılabilir bakiye,
     * izole marjin ve canlı mark-to-market kâr/zarar kalemlerini BigDecimal hassasiyetiyle toplar.
     */
    private List<Map<String, Object>> querySessionMaps(UUID ownerId, UUID sessionIdFilter) {
        ensureMetadataTable();
        Map<UUID, BigDecimal> unrealizedBySession = new HashMap<>();
        Map<UUID, Integer> openCountBySession = new HashMap<>();

        StringBuilder posSql = new StringBuilder("""
                SELECT p.session_id, p.instrument_id, p.side, p.q, p.entry_price
                FROM positions p
                JOIN simulation_sessions s ON s.id = p.session_id
                LEFT JOIN session_metadata m ON m.session_id = s.id
                WHERE s.owner_id = ? AND COALESCE(m.deleted, false) = false
                  AND p.state IN ('OPEN', 'LIQUIDATING')
                """);
        List<Object> posParams = new ArrayList<>();
        posParams.add(ownerId);
        if (sessionIdFilter != null) {
            posSql.append(" AND p.session_id = ?");
            posParams.add(sessionIdFilter);
        }

        jdbc.query(posSql.toString(), rs -> {
            UUID sid = rs.getObject("session_id", UUID.class);
            UUID instrumentId = rs.getObject("instrument_id", UUID.class);
            String side = rs.getString("side");
            BigDecimal q = rs.getBigDecimal("q");
            BigDecimal entry = rs.getBigDecimal("entry_price");
            if (q != null && q.signum() > 0 && entry != null && entry.signum() > 0) {
                BigDecimal mark = catalog.fetchLiveMarkPrice(instrumentId, entry);
                BigDecimal unreal = "LONG".equals(side)
                        ? mark.subtract(entry).multiply(q)
                        : entry.subtract(mark).multiply(q);
                unrealizedBySession.merge(sid, unreal, BigDecimal::add);
            }
            openCountBySession.merge(sid, 1, Integer::sum);
        }, posParams.toArray());

        StringBuilder sql = new StringBuilder("""
                SELECT
                    s.id,
                    m.name AS session_name,
                    s.mode,
                    s.status,
                    s.initial_deposit,
                    s.rule_profile_id,
                    s.risk_policy_id,
                    s.version,
                    s.created_at,
                    COALESCE((
                        SELECT w.balance
                        FROM wallet_accounts w
                        WHERE w.session_id = s.id AND w.kind = 'AVAILABLE'
                        LIMIT 1
                    ), s.initial_deposit) AS available_balance,
                    COALESCE((
                        SELECT SUM(p.initial_margin_remaining)
                        FROM positions p
                        WHERE p.session_id = s.id AND p.state IN ('OPEN', 'LIQUIDATING')
                    ), 0) AS isolated_margin,
                    (
                        SELECT h.reason
                        FROM halt_reasons h
                        WHERE h.session_id = s.id
                        ORDER BY h.created_at DESC
                        LIMIT 1
                    ) AS halt_reason
                FROM simulation_sessions s
                LEFT JOIN session_metadata m ON m.session_id = s.id
                WHERE s.owner_id = ? AND COALESCE(m.deleted, false) = false
                """);
        List<Object> params = new ArrayList<>();
        params.add(ownerId);
        if (sessionIdFilter != null) {
            sql.append(" AND s.id = ?");
            params.add(sessionIdFilter);
        } else {
            sql.append(" ORDER BY s.created_at DESC");
        }

        return jdbc.query(
                sql.toString(),
                (rs, rowNum) -> {
                    UUID sid = rs.getObject("id", UUID.class);
                    BigDecimal initialDeposit = rs.getBigDecimal("initial_deposit");
                    BigDecimal available = rs.getBigDecimal("available_balance");
                    BigDecimal isolated = rs.getBigDecimal("isolated_margin");
                    BigDecimal unrealized = Decimal.cash(unrealizedBySession.getOrDefault(sid, BigDecimal.ZERO));
                    BigDecimal realized = Decimal.cash(available.add(isolated).subtract(initialDeposit));
                    BigDecimal equity = available.add(isolated).add(unrealized);
                    BigDecimal netPnl = Decimal.cash(equity.subtract(initialDeposit));
                    BigDecimal netPnlPercent = initialDeposit != null && initialDeposit.signum() > 0
                            ? netPnl.multiply(new BigDecimal("100"))
                                    .divide(initialDeposit, 2, RoundingMode.HALF_UP)
                            : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
                    int openPositionCount = openCountBySession.getOrDefault(sid, 0);
                    Instant createdAt = rs.getTimestamp("created_at").toInstant();

                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", sid.toString());
                    row.put("name", rs.getString("session_name"));
                    row.put("mode", rs.getString("mode"));
                    row.put("status", rs.getString("status"));
                    row.put("currency", "USDT");
                    row.put("initialDeposit", Decimal.wire(initialDeposit));
                    row.put("availableBalance", Decimal.wire(available));
                    row.put("isolatedMargin", Decimal.wire(isolated));
                    row.put("equity", Decimal.wire(equity));
                    row.put("unrealizedPnl", Decimal.wire(unrealized));
                    row.put("realizedPnl", Decimal.wire(realized));
                    row.put("netPnl", Decimal.wire(netPnl));
                    row.put("netPnlPercent", Decimal.wire(netPnlPercent));
                    row.put("openPositionCount", openPositionCount);
                    row.put("ruleProfileId", rs.getObject("rule_profile_id", UUID.class).toString());
                    row.put("riskPolicyId", rs.getObject("risk_policy_id", UUID.class).toString());
                    row.put("generation", rs.getLong("version"));
                    row.put("haltReason", rs.getString("halt_reason"));
                    row.put("createdAt", createdAt.toString());
                    row.put("updatedAt", createdAt.toString());
                    return row;
                },
                params.toArray()
        );
    }

    private void ensureMetadataTable() {
        reportingService.ensureTables();
    }

    private static BigDecimal parseDerivedDecimal(String raw) {
        BigDecimal value = new BigDecimal(raw.trim());
        return value.scale() > 18 ? value.setScale(18, java.math.RoundingMode.HALF_EVEN) : value;
    }

    private long ensureGeneration(UUID sessionId, UUID instrumentId) {
        var gens = jdbc.queryForList(
                "SELECT generation FROM instrument_generations WHERE session_id = ? AND instrument_id = ?",
                Long.class,
                sessionId,
                instrumentId
        );
        if (gens.isEmpty()) {
            jdbc.update(
                    "INSERT INTO instrument_generations(session_id, instrument_id, generation) VALUES (?, ?, 1) ON CONFLICT DO NOTHING",
                    sessionId,
                    instrumentId
            );
            return 1L;
        }
        return gens.getFirst();
    }

    private BigDecimal resolveReferencePrice(UUID instrumentId, JsonNode body) {
        if (body.hasNonNull("limitPrice") && !body.get("limitPrice").asText().isBlank()) {
            return Decimal.parse(body.get("limitPrice").asText());
        }
        return catalog.fetchLiveMarkPrice(instrumentId, catalog.defaultReferencePrice(instrumentId));
    }

    private UUID resolveOwnedSessionId(UUID ownerId, String rawSessionId) {
        ensureMetadataTable();
        if (rawSessionId != null && !rawSessionId.isBlank()) {
            try {
                UUID candidate = UUID.fromString(rawSessionId.trim());
                var found = jdbc.queryForList("""
                        SELECT s.id
                        FROM simulation_sessions s
                        LEFT JOIN session_metadata m ON m.session_id = s.id
                        WHERE s.id = ? AND s.owner_id = ? AND COALESCE(m.deleted, false) = false
                        """, UUID.class, candidate, ownerId);
                if (!found.isEmpty()) {
                    return found.getFirst();
                }
            } catch (IllegalArgumentException ignored) {
            }
        }

        var latest = jdbc.queryForList("""
                SELECT s.id
                FROM simulation_sessions s
                LEFT JOIN session_metadata m ON m.session_id = s.id
                WHERE s.owner_id = ? AND COALESCE(m.deleted, false) = false
                ORDER BY (CASE WHEN s.status = 'RUNNING' THEN 0 ELSE 1 END), s.created_at DESC
                LIMIT 1
                """, UUID.class, ownerId);
        if (!latest.isEmpty()) {
            return latest.getFirst();
        }

        UUID createdId = createSession.execute(
                ownerId,
                new BigDecimal("10000"),
                DEFAULT_RULE_PROFILE_ID,
                DEFAULT_RISK_POLICY_ID
        );
        jdbc.update("""
                INSERT INTO session_metadata(session_id, name, deleted, updated_at)
                VALUES (?, 'Ana Sanal Cüzdan', false, ?)
                ON CONFLICT (session_id) DO NOTHING
                """, createdId, Timestamp.from(clock.instant()));
        return createdId;
    }

    private static BigDecimal inferTickSize(BigDecimal price) {
        if (price.compareTo(new BigDecimal("100")) >= 0) {
            return new BigDecimal("0.01");
        }
        if (price.compareTo(new BigDecimal("1")) >= 0) {
            return new BigDecimal("0.001");
        }
        return new BigDecimal("0.00001");
    }

    private static BigDecimal inferStepSize(BigDecimal price) {
        if (price.compareTo(new BigDecimal("1000")) >= 0) {
            return new BigDecimal("0.001");
        }
        if (price.compareTo(new BigDecimal("10")) >= 0) {
            return new BigDecimal("0.01");
        }
        if (price.compareTo(new BigDecimal("1")) >= 0) {
            return new BigDecimal("0.1");
        }
        return BigDecimal.ONE;
    }

    private ExecutionPlan previewAiProposalFromStrategyService(
            UUID ownerId,
            String bearerToken,
            UUID sessionId,
            AiPreviewRequest aiReq,
            BigDecimal equity,
            RiskPolicyEvaluator.Limits limits,
            PreviewFactory.Rule rule
    ) throws Exception {
        if (bearerToken == null || bearerToken.isBlank()) {
            throw new org.springframework.security.access.AccessDeniedException("UNAUTHORIZED");
        }
        var reqBuilder = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(
                        "http://127.0.0.1:8084/api/v1/strategy/proposals/"
                                + aiReq.proposalId()
                                + "?revision=" + aiReq.proposalRevision()
                ))
                .header("Authorization", "Bearer " + bearerToken)
                .timeout(java.time.Duration.ofMillis(2000))
                .GET();
        var resp = httpClient.send(reqBuilder.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new BusinessException(BusinessException.Code.NOT_FOUND);
        }
        JsonNode root = objectMapper.readTree(resp.body());
        UUID instrumentId = catalog.resolveInstrumentUuid(root.path("instrumentId").asText("BTCUSDT"));
        JsonNode intent = root.path("intent");
        BigDecimal refPrice = catalog.fetchLiveMarkPrice(
                instrumentId,
                !intent.path("referencePrice").isNull() && !intent.path("referencePrice").asText().isBlank()
                        ? Decimal.parse(intent.path("referencePrice").asText())
                        : new BigDecimal("71480.00")
        );
        BigDecimal tickSize = inferTickSize(refPrice);
        long currentGen = ensureGeneration(sessionId, instrumentId);
        Instant now = clock.instant();

        var snapshot = new com.crypto.simulationservice.application.port.ProposalReader.ProposalSnapshot(
                aiReq.proposalId(),
                aiReq.proposalRevision(),
                ownerId,
                sessionId,
                instrumentId,
                currentGen,
                intent.path("side").asText("LONG"),
                intent.path("orderType").asText("MARKET"),
                !intent.path("limitPrice").isNull() && !intent.path("limitPrice").asText().isBlank()
                        ? Decimal.parse(intent.path("limitPrice").asText())
                        : null,
                !intent.path("stopLoss").isNull() && !intent.path("stopLoss").asText().isBlank()
                        ? Decimal.parse(intent.path("stopLoss").asText())
                        : null,
                !intent.path("takeProfit").isNull() && !intent.path("takeProfit").asText().isBlank()
                        ? Decimal.parse(intent.path("takeProfit").asText())
                        : null,
                Decimal.parse(intent.path("suggestedLeverage").asText("2")),
                intent.path("horizonSeconds").asInt(3600),
                intent.path("expiryPolicy").asText("CLOSE_AT_DEADLINE"),
                now.plusSeconds(120),
                "READY"
        );

        var liveContext = new PreviewFactory.Context(
                refPrice.subtract(tickSize),
                refPrice.add(tickSize),
                equity,
                new BigDecimal("5"),
                1L,
                60L,
                refPrice.multiply(new BigDecimal("0.90")),
                refPrice.multiply(new BigDecimal("1.10")),
                equity,
                false,
                false
        );

        BigDecimal stepSize = inferStepSize(refPrice);
        var effectiveRule = new PreviewFactory.Rule(
                rule.id(),
                tickSize,
                stepSize,
                rule.minNotional(),
                SimulationMarketCatalog.resolveTakerFeeRate(rule.id(), intent.path("orderType").asText("MARKET")),
                rule.tiers()
        );

        ExecutionPlan unsigned = PreviewFactory.createFromAiProposal(
                snapshot,
                1L,
                currentGen,
                equity,
                BigDecimal.ZERO,
                new BigDecimal("0.001"),
                limits,
                liveContext,
                effectiveRule,
                now
        );
        String canonicalJson = new String(PlanHash.canonical(unsigned), StandardCharsets.UTF_8);
        String hash = PlanHash.hash(unsigned);

        ExecutionPlan signed = new ExecutionPlan(
                unsigned.planId(),
                unsigned.revision(),
                hash,
                unsigned.sessionId(),
                unsigned.instrumentId(),
                unsigned.instrumentVersion(),
                unsigned.source(),
                unsigned.proposalId(),
                unsigned.positionGeneration(),
                unsigned.side(),
                unsigned.orderType(),
                unsigned.quantity(),
                unsigned.referenceEntry(),
                unsigned.limitPrice(),
                unsigned.marginRequired(),
                unsigned.openFeeEstimate(),
                unsigned.notional(),
                unsigned.leverage(),
                unsigned.stopLoss(),
                unsigned.takeProfit(),
                unsigned.horizonSeconds(),
                unsigned.expiryPolicy(),
                unsigned.priceLowerBound(),
                unsigned.priceUpperBound(),
                unsigned.maxOpenFee(),
                unsigned.maxSlippageBps(),
                unsigned.ruleProfileId(),
                unsigned.riskPolicyVersion(),
                unsigned.createdAt(),
                unsigned.validUntil(),
                unsigned.estimatedLiquidationPrice(),
                unsigned.warnings()
        );

        jdbc.update("""
                INSERT INTO execution_plans(
                    plan_id,
                    revision,
                    session_id,
                    instrument_id,
                    canonical_json,
                    hash,
                    position_generation,
                    valid_until,
                    state
                )
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, 'PREVIEWED')
                """,
                signed.planId(),
                signed.revision(),
                signed.sessionId(),
                signed.instrumentId(),
                canonicalJson,
                signed.planHash(),
                signed.positionGeneration(),
                Timestamp.from(signed.validUntil())
        );

        return signed;
    }

    private Map<String, Object> toPlanMap(ExecutionPlan plan) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("planId", plan.planId().toString());
        m.put("revision", plan.revision());
        m.put("planHash", plan.planHash());
        m.put("sessionId", plan.sessionId().toString());
        m.put("instrumentId", plan.instrumentId().toString());
        m.put("instrumentVersion", plan.instrumentVersion());
        m.put("source", plan.source());
        m.put("proposalId", uuidOrNull(plan.proposalId()));
        m.put("positionGeneration", plan.positionGeneration());
        m.put("side", plan.side());
        m.put("orderType", plan.orderType());
        m.put("quantity", wireOrNull(plan.quantity()));
        m.put("referenceEntry", wireOrNull(plan.referenceEntry()));
        m.put("limitPrice", wireOrNull(plan.limitPrice()));
        m.put("marginRequired", wireOrNull(plan.marginRequired()));
        m.put("openFeeEstimate", wireOrNull(plan.openFeeEstimate()));
        m.put("notional", wireOrNull(plan.notional()));
        m.put("leverage", wireOrNull(plan.leverage()));
        m.put("stopLoss", wireOrNull(plan.stopLoss()));
        m.put("takeProfit", wireOrNull(plan.takeProfit()));
        m.put("horizonSeconds", plan.horizonSeconds());
        m.put("expiryPolicy", plan.expiryPolicy());
        m.put("priceLowerBound", wireOrNull(plan.priceLowerBound()));
        m.put("priceUpperBound", wireOrNull(plan.priceUpperBound()));
        m.put("maxOpenFee", wireOrNull(plan.maxOpenFee()));
        m.put("maxSlippageBps", wireOrNull(plan.maxSlippageBps()));
        m.put("ruleProfileId", plan.ruleProfileId().toString());
        m.put("riskPolicyVersion", plan.riskPolicyVersion());
        m.put("createdAt", plan.createdAt().toString());
        m.put("validUntil", plan.validUntil().toString());
        m.put("estimatedLiquidationPrice", wireOrNull(plan.estimatedLiquidationPrice()));
        m.put("warnings", plan.warnings());
        return m;
    }

    private static String wireOrNull(BigDecimal value) {
        return value != null ? Decimal.wire(value) : null;
    }

    private static String uuidOrNull(UUID value) {
        return value != null ? value.toString() : null;
    }

    private static RiskPolicyEvaluator.Limits resolveRiskLimits(UUID riskPolicyId, BigDecimal equity) {
        BigDecimal safeEquity = equity != null && equity.signum() > 0 ? equity : new BigDecimal("1000");
        String policyStr = riskPolicyId != null ? riskPolicyId.toString() : "";
        if ("99999999-9999-4999-8999-999999999998".equals(policyStr)) {
            return new RiskPolicyEvaluator.Limits(
                    new BigDecimal("5"),
                    5,
                    new BigDecimal("250000"),
                    new BigDecimal("500000"),
                    safeEquity.multiply(new BigDecimal("0.25")).max(new BigDecimal("100")),
                    new BigDecimal("0.01"),
                    new BigDecimal("0.02")
            );
        }
        if ("99999999-9999-4999-8999-999999999997".equals(policyStr)) {
            return new RiskPolicyEvaluator.Limits(
                    new BigDecimal("50"),
                    15,
                    new BigDecimal("5000000"),
                    new BigDecimal("10000000"),
                    safeEquity.max(new BigDecimal("100")),
                    new BigDecimal("0.05"),
                    new BigDecimal("0.10")
            );
        }
        return new RiskPolicyEvaluator.Limits(
                new BigDecimal("20"),
                8,
                new BigDecimal("500000"),
                new BigDecimal("1000000"),
                safeEquity.multiply(new BigDecimal("0.50")).max(new BigDecimal("200")),
                new BigDecimal("0.02"),
                new BigDecimal("0.05")
        );
    }

    private SseRing ringFor(UUID sessionId) {
        return streamRegistry.ringFor(sessionId);
    }

    private static UUID parseUuidOrDefault(Object raw, UUID fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return UUID.fromString(raw.toString().trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
