package com.crypto.simulationservice.domain.model.replay;

import java.time.Instant;
import java.util.UUID;

/**
 * Sorumluluk: İş verisini ve kendi değişmez kurallarını temsil eder.
 * Okuma ipucu: Modelin HTTP veya veritabanı biçimine bağımlı olup olmadığını kontrol et.
 */
public record ReplayInput(UUID id, Instant time, Kind kind, long originalSequence, String payload) {

    public enum Kind {
        STATUS_BOUNDARY(1),
        FUNDING_SETTLEMENT(2),
        MARK(3),
        BRACKET(4),
        QUOTE_EXIT(5),
        ENTRY_FILL(6),
        CANDLE_CLOSE(7);

        private final int priority;

        Kind(int priority) {
            this.priority = priority;
        }

        public int priority() {
            return priority;
        }
    }
    // payload üretildiğinde canonical/hash kontrolü yapılır; replay zamanı değiştirilmez.
}

