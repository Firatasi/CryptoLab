package com.crypto.strategyservice.application.service;

import com.crypto.strategyservice.application.port.MarketUniversePort;
import com.crypto.strategyservice.application.repository.ScanJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class ScanService {

    public static final int MAX_PAGE_LIMIT = 200;
    private static final Set<Integer> SUPPORTED_HORIZONS = Set.of(60, 900, 3600, 86400, 604800);
    private static final Duration LEASE_DURATION = Duration.ofSeconds(30);

    private final ScanJobRepository scans;
    private final MarketUniversePort universePort;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ScanService(
            ScanJobRepository scans,
            MarketUniversePort universePort,
            TransactionTemplate tx,
            Clock clock
    ) {
        this.scans = scans;
        this.universePort = universePort;
        this.tx = tx;
        this.clock = clock;
    }

    public UUID createScan(
            UUID ownerId,
            UUID sessionId,
            int horizonSeconds,
            String cursor
    ) {
        if (!SUPPORTED_HORIZONS.contains(horizonSeconds)) {
            throw new IllegalArgumentException("INVALID_HORIZON");
        }
        var snapshot = universePort.currentUniverse(horizonSeconds);
        int startIndex = parseCursor(cursor, snapshot.universeVersion(), snapshot.candidates().size());
        String initialCursor = startIndex == 0 ? null : formatCursor(snapshot.universeVersion(), startIndex);

        UUID scanId = UUID.randomUUID();
        Instant now = clock.instant();
        tx.executeWithoutResult(status -> scans.createScan(
                scanId,
                ownerId,
                sessionId,
                horizonSeconds,
                snapshot.universeVersion(),
                initialCursor,
                snapshot.candidates().size(),
                now
        ));
        return scanId;
    }

    public ScanJobRepository.ScanJobView processPage(
            UUID ownerId,
            UUID scanId,
            int pageLimit,
            int maxAnalysesBudget,
            Instant asOf
    ) {
        if (pageLimit <= 0 || pageLimit > MAX_PAGE_LIMIT) {
            throw new IllegalArgumentException("INVALID_SCAN_PAGE_LIMIT");
        }
        if (maxAnalysesBudget <= 0) {
            throw new IllegalArgumentException("INVALID_SCAN_BUDGET");
        }

        var currentView = tx.execute(s -> scans.findScanView(ownerId, scanId))
                .orElseThrow(() -> new IllegalStateException("SCAN_NOT_FOUND"));

        if ("CANCELLED".equals(currentView.status()) || "SUCCEEDED".equals(currentView.status())) {
            return currentView;
        }

        var snapshot = universePort.currentUniverse(currentView.horizonSeconds());
        if (!snapshot.universeVersion().equals(currentView.universeVersion())) {
            throw new IllegalStateException("UNIVERSE_SNAPSHOT_CHANGED");
        }

        int startIndex = parseCursor(currentView.cursor(), snapshot.universeVersion(), snapshot.candidates().size());
        UUID workerToken = UUID.randomUUID();
        Instant claimAt = clock.instant();

        Boolean claimed = tx.execute(s -> scans.claimScan(
                scanId,
                workerToken,
                claimAt,
                claimAt.plus(LEASE_DURATION)
        ));
        if (!Boolean.TRUE.equals(claimed)) {
            return tx.execute(s -> scans.findScanView(ownerId, scanId)).orElseThrow();
        }

        int processedInPage = 0;
        int scheduledInPage = 0;
        int idx = startIndex;
        var candidates = snapshot.candidates();

        while (idx < candidates.size() && processedInPage < pageLimit) {
            Boolean cancelled = tx.execute(s -> scans.isCancelled(scanId));
            if (Boolean.TRUE.equals(cancelled)) {
                return tx.execute(s -> scans.findScanView(ownerId, scanId)).orElseThrow();
            }

            var candidate = candidates.get(idx);
            if (candidate.eligible() && scheduledInPage >= maxAnalysesBudget) {
                // Bütçe bitti: PARTIAL durumuna geç ve kaldığı yerin cursor'ını kaydet;
                // asla "tüm piyasa tarandı" (SUCCEEDED) deme.
                String nextCursor = formatCursor(snapshot.universeVersion(), idx);
                tx.executeWithoutResult(s -> scans.finishScanStep(
                        scanId,
                        workerToken,
                        "PARTIAL",
                        nextCursor,
                        clock.instant()
                ));
                return tx.execute(s -> scans.findScanView(ownerId, scanId)).orElseThrow();
            }

            final int positionIndex = idx;
            var outcome = new ScanJobRepository.CandidateOutcome(
                    positionIndex,
                    candidate.instrumentId(),
                    candidate.eligible(),
                    candidate.skippedReason(),
                    candidate.eligible() ? UUID.randomUUID() : null
            );

            Boolean recorded = tx.execute(s -> scans.recordCandidateStep(
                    scanId,
                    workerToken,
                    outcome,
                    asOf,
                    clock.instant()
            ));
            if (!Boolean.TRUE.equals(recorded)) {
                // Lease kaybedildi veya dışarıdan CANCELLED yapıldı.
                return tx.execute(s -> scans.findScanView(ownerId, scanId)).orElseThrow();
            }

            if (candidate.eligible()) {
                scheduledInPage++;
            }
            processedInPage++;
            idx++;
        }

        final int finalIndex = idx;
        if (finalIndex < candidates.size()) {
            String nextCursor = formatCursor(snapshot.universeVersion(), finalIndex);
            tx.executeWithoutResult(s -> scans.finishScanStep(
                    scanId,
                    workerToken,
                    "PARTIAL",
                    nextCursor,
                    clock.instant()
            ));
        } else {
            tx.executeWithoutResult(s -> scans.finishScanStep(
                    scanId,
                    workerToken,
                    "SUCCEEDED",
                    null,
                    clock.instant()
            ));
        }

        return tx.execute(s -> scans.findScanView(ownerId, scanId)).orElseThrow();
    }

    public boolean cancel(UUID ownerId, UUID scanId) {
        return Boolean.TRUE.equals(tx.execute(s -> scans.cancel(ownerId, scanId, clock.instant())));
    }

    public Optional<ScanJobRepository.ScanJobView> findView(UUID ownerId, UUID scanId) {
        return tx.execute(s -> scans.findScanView(ownerId, scanId));
    }

    public static String formatCursor(String universeVersion, int index) {
        return "cursor:" + universeVersion + ":" + index;
    }

    public static int parseCursor(String cursor, String expectedUniverseVersion, int totalSize) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        String prefix = "cursor:" + expectedUniverseVersion + ":";
        if (!cursor.startsWith(prefix)) {
            throw new IllegalArgumentException("INVALID_SCAN_CURSOR");
        }
        try {
            int index = Integer.parseInt(cursor.substring(prefix.length()));
            if (index < 0 || index > totalSize) {
                throw new IllegalArgumentException("INVALID_SCAN_CURSOR");
            }
            return index;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("INVALID_SCAN_CURSOR", e);
        }
    }
}

