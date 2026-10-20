package com.crypto.simulationservice.application.repository;

import java.time.Instant;
import java.util.UUID;

/**
 * Sorumluluk: Kalıcılık davranışını arayüz olarak tanımlar. Okuma ipucu:
 * Service bu arayüze bağımlıdır; SQL implementasyonu infrastructure altındadır.
 */
public interface DeadlineRepository {

    void schedule(UUID position, UUID session, long generation, int horizon, String kind);

    boolean claim(UUID position, long generation, String kind, Instant now);
}
