package com.crypto.simulationservice.infrastructure.repository;

import com.crypto.simulationservice.application.repository.SessionRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcSessionRepository implements SessionRepository {

    private final JdbcTemplate jdbc;

    public JdbcSessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void create(
            UUID session,
            UUID owner,
            BigDecimal deposit,
            UUID rule,
            UUID risk,
            Instant now
    ) {
        jdbc.update("""
                INSERT INTO simulation_sessions (
                    id,
                    owner_id,
                    mode,
                    initial_deposit,
                    rule_profile_id,
                    risk_policy_id,
                    status,
                    created_at
                )
                VALUES (?, ?, 'LIVE_PAPER', ?, ?, ?, 'RUNNING', ?)
                """,
                session,
                owner,
                deposit,
                rule,
                risk,
                Timestamp.from(now)
        );
    }

    @Override
    public void createInitialAccounts(
            UUID session,
            UUID available,
            UUID capital
    ) {
        // balance alanı veritabanındaki DEFAULT 0 değerini alır.
        jdbc.update("""
                INSERT INTO wallet_accounts (
                    id,
                    session_id,
                    account_key,
                    kind
                )
                VALUES
                    (?, ?, 'AVAILABLE', 'AVAILABLE'),
                    (?, ?, 'INITIAL_CAPITAL', 'SYSTEM')
                """,
                available,
                session,
                capital,
                session
        );
    }

    @Override
    public long lock(UUID session, UUID owner) {
        var versions = jdbc.query("""
            SELECT version
            FROM simulation_sessions
            WHERE id = ? AND owner_id = ?
            FOR UPDATE
            """,
                (rs, rowNum) -> rs.getLong("version"),
                session,
                owner
        );

        if (versions.isEmpty()) {
            throw new IllegalStateException("SESSION_NOT_FOUND");
        }

        return versions.get(0);
    }

    @Override
    public void incrementVersion(
            UUID session,
            UUID owner,
            long expectedVersion
    ) {
        int changed = jdbc.update("""
            UPDATE simulation_sessions
            SET version = version + 1
            WHERE id = ?
              AND owner_id = ?
              AND version = ?
            """,
                session,
                owner,
                expectedVersion
        );

        if (changed != 1) {
            throw new IllegalStateException("SESSION_VERSION_CONFLICT");
        }
    }
}