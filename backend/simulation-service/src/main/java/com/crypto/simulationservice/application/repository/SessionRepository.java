package com.crypto.simulationservice.application.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public interface SessionRepository {

    // Simülasyon oturumunu oluşturur.
    void create(
            UUID session,
            UUID owner,
            BigDecimal deposit,
            UUID rule,
            UUID risk,
            Instant now
    );

    // Kullanılabilir bakiye ve başlangıç sermayesi karşı hesabını
    // sıfır bakiyeyle oluşturur.
    void createInitialAccounts(
            UUID session,
            UUID available,
            UUID capital
    );

    // Session'ı sahibiyle birlikte doğrular, kilitler ve mevcut version'ı döndürür.
    long lock(UUID session, UUID owner);

    // Başarılı değişiklik sonunda version'ı bir artırır.
    void incrementVersion(UUID session, UUID owner, long expectedVersion);
}