package com.crypto.simulationservice.application.replay;

import java.util.UUID;

/**
 * Sorumluluk: Tarihsel çalışmanın yürütülmesini yönetir.
 * Okuma ipucu: Sanal saat ve deterministik sıra gerçek sistem saatinden bağımsız tutulur.
 */
public record ReplayScope(UUID runId, UUID sessionId, String sessionMode, UUID policyArtifactId) {

    public ReplayScope {
        if (!"HISTORICAL_REPLAY".equals(sessionMode)) {
            throw new IllegalArgumentException("REPLAY_SCOPE_REQUIRED");
        }
    }

    public void verify(UUID targetSession, UUID suppliedRun) {
        // İç eval endpoint'i LIVE_PAPER onay kapısını dolaşamaz.
        if (!sessionId.equals(targetSession) || !runId.equals(suppliedRun)) {
            throw new IllegalArgumentException("REPLAY_SCOPE_MISMATCH");
        }
    }
}

