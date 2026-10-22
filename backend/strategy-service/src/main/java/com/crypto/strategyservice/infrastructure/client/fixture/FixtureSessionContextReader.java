package com.crypto.strategyservice.infrastructure.client.fixture;

import com.crypto.strategyservice.application.port.SessionContextReader;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class FixtureSessionContextReader implements SessionContextReader {

    private final Map<Key, SessionSnapshot> sessions = new ConcurrentHashMap<>();
    private volatile boolean allowUnregisteredAsRunning = true;

    private record Key(UUID ownerId, UUID sessionId, UUID instrumentId) {
    }

    public void register(SessionSnapshot snapshot) {
        sessions.put(new Key(snapshot.ownerId(), snapshot.sessionId(), snapshot.instrumentId()), snapshot);
    }

    public void setAllowUnregisteredAsRunning(boolean allow) {
        this.allowUnregisteredAsRunning = allow;
    }

    public void clear() {
        sessions.clear();
        allowUnregisteredAsRunning = true;
    }

    @Override
    public Optional<SessionSnapshot> findOwnedSession(UUID ownerId, UUID sessionId, UUID instrumentId) {
        SessionSnapshot registered = sessions.get(new Key(ownerId, sessionId, instrumentId));
        if (registered != null) {
            return Optional.of(registered);
        }
        if (allowUnregisteredAsRunning) {
            return Optional.of(new SessionSnapshot(sessionId, ownerId, instrumentId, 1L, "RUNNING", 0.002));
        }
        return Optional.empty();
    }
}

