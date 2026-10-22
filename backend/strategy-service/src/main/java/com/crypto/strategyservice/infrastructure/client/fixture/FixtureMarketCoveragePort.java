package com.crypto.strategyservice.infrastructure.client.fixture;

import com.crypto.strategyservice.application.port.MarketCoveragePort;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class FixtureMarketCoveragePort implements MarketCoveragePort {

    public record BackfillCall(UUID backfillId, UUID instrumentId, int horizonSeconds, Instant asOf) {
    }

    private static final UUID DEFAULT_DATASET_ID = UUID.fromString("77777777-7777-4777-8777-777777777777");
    private static final UUID DEFAULT_SNAPSHOT_ID = UUID.fromString("88888888-8888-4888-8888-888888888888");

    private final Map<UUID, MarketEvaluation> evaluationsByInstrument = new ConcurrentHashMap<>();
    private final List<BackfillCall> backfillCalls = new CopyOnWriteArrayList<>();

    public void givenEvaluation(UUID instrumentId, MarketEvaluation evaluation) {
        evaluationsByInstrument.put(instrumentId, evaluation);
    }

    public List<BackfillCall> backfillCalls() {
        return List.copyOf(backfillCalls);
    }

    public void clear() {
        evaluationsByInstrument.clear();
        backfillCalls.clear();
    }

    @Override
    public MarketEvaluation evaluate(UUID instrumentId, int horizonSeconds, Instant asOf) {
        MarketEvaluation custom = evaluationsByInstrument.get(instrumentId);
        if (custom != null) {
            return custom;
        }
        return new MarketEvaluation(
                DEFAULT_DATASET_ID,
                DEFAULT_SNAPSHOT_ID,
                asOf,
                1.0,
                new BigDecimal("100"),
                new BigDecimal("5"),
                true
        );
    }

    @Override
    public void triggerBackfill(UUID backfillId, UUID instrumentId, int horizonSeconds, Instant asOf) {
        backfillCalls.add(new BackfillCall(backfillId, instrumentId, horizonSeconds, asOf));
    }
}

