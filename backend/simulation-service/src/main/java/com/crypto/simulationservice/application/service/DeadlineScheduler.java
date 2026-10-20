package com.crypto.simulationservice.application.service;

import org.springframework.stereotype.Service;
import com.crypto.simulationservice.application.repository.DeadlineRepository;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.sql.Timestamp;
import java.util.UUID;

/**
 * Sorumluluk: Bir kullanım senaryosunun sırasını yönetir. Okuma ipucu:
 * Constructor bağımlılıklarını ve transaction sınırını önce oku; hesap ve SQL
 * sorumluluklarını ayır.
 */
@Service
public class DeadlineScheduler {

    private final DeadlineRepository db;
    private final Clock clock;

    public DeadlineScheduler(DeadlineRepository db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    @Transactional
    public void schedule(UUID position, UUID session, long generation, int horizon, String kind) {
        // Kayıt ilk fill transaction'ının içinden çağrılır; opened_at first fill'dir.
        db.schedule(position, session, generation, horizon, kind);
    }

    @Transactional
    public boolean claim(UUID position, long generation, String kind) {
        // Poller önce session lock almalı; due ve position kilit sırası ortak kalmalı.
        return db.claim(position, generation, kind, clock.instant());
    }
}
