package com.crypto.simulationservice.application.replay;

import com.crypto.simulationservice.domain.model.replay.ReplayInput;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.BooleanSupplier;

/**
 * Sorumluluk: Tarihsel çalışmanın yürütülmesini yönetir.
 * Okuma ipucu: Sanal saat ve deterministik sıra gerçek sistem saatinden bağımsız tutulur.
 */
public final class ReplayRunner {

    public interface Engine {
        // Aynı finance-core ve session writer uygulaması bağlanır; ikinci para motoru değil.
        void accept(ReplayInput input, VirtualClock clock, SplittableRandom random);
    }

    public record Result(String status, long applied, String fidelity) {
    }

    public Result run(
            List<ReplayInput> input,
            boolean recordedJournal,
            long seed,
            VirtualClock clock,
            Engine engine,
            BooleanSupplier cancelled,
            String fidelity
    ) {
        List<ReplayInput> ordered = new ArrayList<>(input);
        if (recordedJournal) {
            ordered.sort(Comparator.comparingLong(ReplayInput::originalSequence));
            long previous = 0;
            for (var e : ordered) {
                if (e.originalSequence() != previous + 1) {
                    throw new IllegalArgumentException("ENGINE_SEQUENCE_GAP");
                }
                previous = e.originalSequence();
            }
        } else {
            // UUID tie-break gerçek piyasa sırası iddiası değildir, tekrarlanabilirlik seçimidir.
            ordered.sort(Comparator.comparing(ReplayInput::time)
                    .thenComparingInt(e -> e.kind().priority())
                    .thenComparing(e -> e.id().toString()));
        }
        return runOrderedIterator(ordered.iterator(), seed, clock, engine, cancelled, fidelity);
    }

    public Result runOrderedIterator(
            Iterator<ReplayInput> orderedIterator,
            long seed,
            VirtualClock clock,
            Engine engine,
            BooleanSupplier cancelled,
            String fidelity
    ) {
        var random = new SplittableRandom(seed);
        long applied = 0;
        while (orderedIterator.hasNext()) {
            if (cancelled.getAsBoolean()) {
                return new Result("CANCELLED", applied, fidelity);
            }
            var e = orderedIterator.next();
            // Journal source time geriye gidebilir. Journal kaydında acceptedTime kullanılmalıdır.
            clock.advanceTo(e.time());
            engine.accept(e, clock, random);
            applied++;
        }
        return new Result("INDETERMINATE".equals(fidelity) ? "INDETERMINATE" : "SUCCEEDED", applied, fidelity);
    }
}

