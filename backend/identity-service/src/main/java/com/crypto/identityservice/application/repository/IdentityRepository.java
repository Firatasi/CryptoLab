package com.crypto.identityservice.application.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IdentityRepository {

    record Registration(
            String hash,
            UUID userId
    ) {
    }

    record Credentials(
            UUID id,
            String passwordHash,
            String status
    ) {
        @Override
        public String toString() {
            return "Credentials[REDACTED]";
        }
    }

    record Refresh(
            UUID id,
            UUID userId,
            UUID familyId,
            Instant expiresAt,
            Instant revokedAt
    ) {
    }

    record User(
            UUID id,
            String displayName,
            List<String> roles
    ) {
    }

    // Aynı kayıt isteğini veya refresh ailesini transaction boyunca kilitler.
    void lock(String scope);

    Optional<Registration> registration(String key);

    void register(
            UUID id,
            String email,
            String name,
            String passwordHash,
            Instant now,
            String key,
            String digest
    );

    Optional<Credentials> credentials(String email);

    void insertRefresh(
            UUID id,
            UUID user,
            String hash,
            UUID family,
            Instant expiry
    );

    Optional<UUID> family(String hash);

    Refresh lockRefresh(String hash);

    void revokeFamily(UUID family, Instant now);

    String status(UUID user);

    UUID refreshId(String hash);

    void replaceRefresh(UUID previous, UUID next, Instant now);

    User user(UUID id);

    List<String> roles(UUID user);

    List<String> authorities(UUID user);
}