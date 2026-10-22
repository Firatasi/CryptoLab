package com.crypto.strategyservice.infrastructure.client.fixture;

import com.crypto.strategyservice.application.port.MarketUniversePort;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class FixtureMarketUniversePort implements MarketUniversePort {

    private volatile UniverseSnapshot snapshot = new UniverseSnapshot(
            "universe-fixture-v1",
            List.of(
                    new Candidate(UUID.fromString("10000000-0000-4000-8000-000000000001"), true, null),
                    new Candidate(UUID.fromString("10000000-0000-4000-8000-000000000002"), false, "INSUFFICIENT_COVERAGE")
            )
    );

    public void setSnapshot(UniverseSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    @Override
    public UniverseSnapshot currentUniverse(int horizonSeconds) {
        return snapshot;
    }
}

