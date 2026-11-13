package com.crypto.simulationservice.infrastructure.streaming;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SessionStreamRegistry {

    private static final int MAX_RINGS = 5_000;
    private final Map<UUID, SseRing> streams = new ConcurrentHashMap<>();

    public SseRing ringFor(UUID sessionId) {
        if (streams.size() >= MAX_RINGS && !streams.containsKey(sessionId)) {
            var it = streams.keySet().iterator();
            if (it.hasNext()) {
                streams.remove(it.next());
            }
        }
        return streams.computeIfAbsent(sessionId, k -> new SseRing());
    }

    public void publish(UUID sessionId, String eventName, Object data) {
        SseRing ring = streams.get(sessionId);
        if (ring != null) {
            ring.publish(eventName, data);
        }
    }
}
