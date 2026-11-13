package com.crypto.simulationservice.application.service;

import com.crypto.financecore.finance.Decimal;
import com.crypto.simulationservice.application.service.SimulationReportingService.AutoExitReason;
import com.crypto.simulationservice.application.service.SimulationReportingService.LiquidatedPositionEvent;
import com.crypto.simulationservice.application.service.SimulationReportingService.PositionExitEvent;
import com.crypto.simulationservice.infrastructure.streaming.SessionStreamRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class LiquidationMonitorService {

    private static final Logger log = LoggerFactory.getLogger(LiquidationMonitorService.class);

    private final SimulationReportingService reportingService;
    private final SessionStreamRegistry streamRegistry;
    private final Clock clock;
    private final boolean enabled;
    private final OrderExecutionService orderExecutionService;

    public LiquidationMonitorService(
            SimulationReportingService reportingService,
            SessionStreamRegistry streamRegistry,
            Clock clock,
            @Value("${simulation.liquidation.enabled:true}") boolean enabled
    ) {
        this(reportingService, streamRegistry, clock, enabled, null);
    }

    @Autowired
    public LiquidationMonitorService(
            SimulationReportingService reportingService,
            SessionStreamRegistry streamRegistry,
            Clock clock,
            @Value("${simulation.liquidation.enabled:true}") boolean enabled,
            OrderExecutionService orderExecutionService
    ) {
        this.reportingService = reportingService;
        this.streamRegistry = streamRegistry;
        this.clock = clock;
        this.enabled = enabled;
        this.orderExecutionService = orderExecutionService;
    }

    @Scheduled(fixedDelayString = "${simulation.liquidation.poll-ms:1000}")
    public void sweepOpenPositions() {
        if (!enabled) {
            return;
        }
        if (orderExecutionService != null) {
            try {
                orderExecutionService.processPendingCommands(null);
            } catch (Exception ex) {
                log.warn("Pending command sweep skipped due to error: {}", ex.getMessage());
            }
            try {
                orderExecutionService.matchOpenLimitOrders(null);
            } catch (Exception ex) {
                log.warn("Limit order matching sweep skipped due to error: {}", ex.getMessage());
            }
        }
        try {
            List<PositionExitEvent> events = reportingService.processAutomaticExits(
                    null,
                    null,
                    clock.instant()
            );
            publishEvents(events);
        } catch (Exception ex) {
            log.warn("Auto-exit sweep skipped due to error: {}", ex.getMessage());
        }
    }

    public List<LiquidatedPositionEvent> checkAndPublish(UUID ownerId, UUID sessionId) {
        List<PositionExitEvent> events = reportingService.processAutomaticExits(
                ownerId,
                sessionId,
                clock.instant()
        );
        publishEvents(events);
        return events.stream()
                .filter(ev -> ev.reason() == AutoExitReason.LIQUIDATED)
                .map(ev -> new LiquidatedPositionEvent(
                        ev.sessionId(),
                        ev.positionId(),
                        ev.instrumentId(),
                        ev.symbol(),
                        ev.side(),
                        ev.markPrice(),
                        ev.closingFee(),
                        ev.insurance(),
                        ev.closedAt()
                ))
                .toList();
    }

    private void publishEvents(List<PositionExitEvent> events) {
        for (PositionExitEvent ev : events) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("positionId", ev.positionId().toString());
            payload.put("sessionId", ev.sessionId().toString());
            payload.put("instrumentId", ev.instrumentId().toString());
            payload.put("symbol", ev.symbol());
            payload.put("side", ev.side());
            payload.put("markPrice", Decimal.wire(ev.markPrice()));
            payload.put("closedAt", ev.closedAt().toString());

            if (ev.reason() == AutoExitReason.LIQUIDATED) {
                payload.put("status", "LIQUIDATED");
                payload.put("liquidationFee", Decimal.wire(ev.closingFee()));
                payload.put("insurance", Decimal.wire(ev.insurance()));
                streamRegistry.publish(ev.sessionId(), "position.liquidated", payload);
            } else {
                payload.put("status", "CLOSED");
                payload.put("reason", ev.reason().name());
                payload.put("exitPrice", Decimal.wire(ev.markPrice()));
                payload.put("closingFee", Decimal.wire(ev.closingFee()));
                payload.put("realizedPnl", Decimal.wire(ev.realizedPnl()));
                streamRegistry.publish(ev.sessionId(), "position.closed", payload);
            }
        }
    }
}
