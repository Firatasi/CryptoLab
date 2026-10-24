package com.crypto.simulationservice;

import com.crypto.simulationservice.infrastructure.streaming.SseRing;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class SseRingTest {

    @Test
    void sseRing_subscribesPublishesAndHandlesUnknownCursorResync() {
        var ring = new SseRing();
        SseEmitter emitter1 = ring.subscribe(null);
        assertNotNull(emitter1);

        ring.publish("session.updated", "{\"seq\":1}");
        ring.heartbeat();

        // Eski / bilinmeyen epoch:sequence ile abone olunduğunda resync.required gönderilir
        SseEmitter emitter2 = ring.subscribe("expired-epoch:99");
        assertNotNull(emitter2);
    }
}

