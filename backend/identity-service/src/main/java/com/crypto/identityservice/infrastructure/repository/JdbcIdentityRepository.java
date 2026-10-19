package com.crypto.identityservice.infrastructure.repository;

import com.crypto.identityservice.application.repository.IdentityRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcIdentityRepository implements IdentityRepository {

    private final JdbcTemplate jdbc;

    public JdbcIdentityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void lock(String scope) {
        // Transaction tamamlanınca PostgreSQL kilidi otomatik bırakır.
        jdbc.queryForList("""
                SELECT pg_advisory_xact_lock(hashtextextended(?, 0))
                """,
                scope
        );
    }

    @Override
    public Optional<Registration> registration(String key) {
        return jdbc.query("""
                SELECT request_hash, user_id
                FROM registration_requests
                WHERE key = ?
                """,
                (rs, rowNum) -> new Registration(
                        rs.getString("request_hash"),
                        rs.getObject("user_id", UUID.class)
                ),
                key
        ).stream().findFirst();
    }

    @Override
    public void register(
            UUID id,
            String email,
            String name,
            String passwordHash,
            Instant now,
            String key,
            String digest
    ) {
        jdbc.update("""
                INSERT INTO users (
                    id,
                    normalized_email,
                    display_name,
                    password_hash,
                    created_at
                )
                VALUES (?, ?, ?, ?, ?)
                """,
                id,
                email,
                name,
                passwordHash,
                Timestamp.from(now)
        );

        // Yeni kullanıcı rolünü istemci seçemez.
        jdbc.update("""
                INSERT INTO user_roles (user_id, role)
                VALUES (?, 'USER')
                """,
                id
        );

        jdbc.update("""
                INSERT INTO registration_requests (
                    key,
                    request_hash,
                    user_id
                )
                VALUES (?, ?, ?)
                """,
                key,
                digest,
                id
        );
    }

    @Override
    public Optional<Credentials> credentials(String email) {
        return jdbc.query("""
                SELECT id, password_hash, status
                FROM users
                WHERE normalized_email = ?
                """,
                (rs, rowNum) -> new Credentials(
                        rs.getObject("id", UUID.class),
                        rs.getString("password_hash"),
                        rs.getString("status")
                ),
                email
        ).stream().findFirst();
    }

    @Override
    public void insertRefresh(
            UUID id,
            UUID user,
            String hash,
            UUID family,
            Instant expiry
    ) {
        jdbc.update("""
                INSERT INTO refresh_sessions (
                    id,
                    user_id,
                    token_hash,
                    family_id,
                    expires_at
                )
                VALUES (?, ?, ?, ?, ?)
                """,
                id,
                user,
                hash,
                family,
                Timestamp.from(expiry)
        );
    }

    @Override
    public Optional<UUID> family(String hash) {
        return jdbc.queryForList("""
                SELECT family_id
                FROM refresh_sessions
                WHERE token_hash = ?
                """,
                UUID.class,
                hash
        ).stream().findFirst();
    }

    @Override
    public Refresh lockRefresh(String hash) {
        return jdbc.queryForObject("""
                SELECT
                    id,
                    user_id,
                    family_id,
                    expires_at,
                    revoked_at
                FROM refresh_sessions
                WHERE token_hash = ?
                FOR UPDATE
                """,
                (rs, rowNum) -> {
                    Timestamp revokedAt = rs.getTimestamp("revoked_at");

                    return new Refresh(
                            rs.getObject("id", UUID.class),
                            rs.getObject("user_id", UUID.class),
                            rs.getObject("family_id", UUID.class),
                            rs.getTimestamp("expires_at").toInstant(),
                            revokedAt == null ? null : revokedAt.toInstant()
                    );
                },
                hash
        );
    }

    @Override
    public void revokeFamily(UUID family, Instant now) {
        jdbc.update("""
                UPDATE refresh_sessions
                SET revoked_at = coalesce(revoked_at, ?),
                    version = version + 1
                WHERE family_id = ?
                """,
                Timestamp.from(now),
                family
        );
    }

    @Override
    public String status(UUID user) {
        return jdbc.queryForObject("""
                SELECT status
                FROM users
                WHERE id = ?
                """,
                String.class,
                user
        );
    }

    @Override
    public UUID refreshId(String hash) {
        return jdbc.queryForObject("""
                SELECT id
                FROM refresh_sessions
                WHERE token_hash = ?
                """,
                UUID.class,
                hash
        );
    }

    @Override
    public void replaceRefresh(
            UUID previous,
            UUID next,
            Instant now
    ) {
        jdbc.update("""
                UPDATE refresh_sessions
                SET revoked_at = ?,
                    replaced_by = ?,
                    version = version + 1
                WHERE id = ?
                """,
                Timestamp.from(now),
                next,
                previous
        );
    }

    @Override
    public User user(UUID id) {
        String displayName = jdbc.queryForObject("""
                SELECT display_name
                FROM users
                WHERE id = ?
                """,
                String.class,
                id
        );

        return new User(id, displayName, roles(id));
    }

    @Override
    public List<String> roles(UUID user) {
        return jdbc.queryForList("""
                SELECT role
                FROM user_roles
                WHERE user_id = ?
                """,
                String.class,
                user
        );
    }

    @Override
    public List<String> authorities(UUID user) {
        return jdbc.queryForList("""
                SELECT authority
                FROM user_authorities
                WHERE user_id = ?
                """,
                String.class,
                user
        );
    }
}