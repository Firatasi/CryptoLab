package com.crypto.simulationservice.application.replay;

import com.crypto.simulationservice.domain.model.replay.FidelityReport;
import com.crypto.simulationservice.domain.model.replay.ReplayInput;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sorumluluk: Davranışı doğrulayan test.
 * Okuma ipucu: Beklenen sonucu bağımsız belirle; testin gerçekten çalıştığını raporla.
 */
class ReplayRunnerTest {

    @Test
    void deterministicTieAndCancel() {
        var t = Instant.parse("2026-01-01T00:00:00Z");
        var quote = new ReplayInput(UUID.fromString("11111111-1111-4111-8111-111111111111"), t, ReplayInput.Kind.QUOTE_EXIT, 2, "{}");
        var mark = new ReplayInput(UUID.fromString("22222222-2222-4222-8222-222222222222"), t, ReplayInput.Kind.MARK, 1, "{}");
        var order = new ArrayList<ReplayInput.Kind>();
        var runner = new ReplayRunner();
        var r = runner.run(List.of(quote, mark), false, 42, new VirtualClock(t), (e, c, random) -> order.add(e.kind()), () -> false, "EXACT_WITHIN_MODEL");
        assertEquals(List.of(ReplayInput.Kind.MARK, ReplayInput.Kind.QUOTE_EXIT), order);
        assertEquals("SUCCEEDED", r.status());
        var cancelled = runner.run(List.of(mark), false, 42, new VirtualClock(t), (e, c, random) -> fail("cancel sonrası işlem yok"), () -> true, "APPROXIMATE");
        assertEquals("CANCELLED", cancelled.status());
        assertEquals(0, cancelled.applied());
    }

    @Test
    void missingMarkCannotBeExact() {
        assertEquals("INDETERMINATE", FidelityReport.classify("TRADE_ONLY", false, true, 0, false).fidelity());
    }
}

