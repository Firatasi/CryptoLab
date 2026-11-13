package com.crypto.identityservice.infrastructure.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Locale;
import java.util.UUID;

@Component
public class AdminAccountBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminAccountBootstrap.class);
    private static final UUID FIXED_ADMIN_ID = UUID.fromString("00000000-0000-4000-8000-00000000a001");

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final String adminEmail;
    private final String adminPassword;
    private final String adminDisplayName;

    public AdminAccountBootstrap(
            JdbcTemplate jdbc,
            PasswordEncoder passwordEncoder,
            Clock clock,
            @Value("${admin.bootstrap.email:admin.com}") String adminEmail,
            @Value("${admin.bootstrap.password:cryptoadmin15}") String adminPassword,
            @Value("${admin.bootstrap.display-name:Sistem Yöneticisi}") String adminDisplayName
    ) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.adminEmail = adminEmail.strip().toLowerCase(Locale.ROOT);
        this.adminPassword = adminPassword;
        this.adminDisplayName = adminDisplayName;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        var existingAdminUsers = jdbc.queryForList("""
                SELECT user_id
                FROM user_roles
                WHERE role = 'ADMIN'
                """, UUID.class);

        var existingByEmail = jdbc.queryForList("""
                SELECT id
                FROM users
                WHERE normalized_email = ?
                """, UUID.class, adminEmail);

        if (!existingAdminUsers.isEmpty()) {
            UUID currentAdminId = existingAdminUsers.getFirst();
            if (!existingByEmail.isEmpty() && !existingByEmail.getFirst().equals(currentAdminId)) {
                log.warn("Sistemde zaten tanımlı tek ADMIN hesabı bulunduğu için bootstrap atlandı.");
                return;
            }
            if (!existingByEmail.isEmpty()) {
                return;
            }
        }

        UUID targetId = existingByEmail.isEmpty() ? FIXED_ADMIN_ID : existingByEmail.getFirst();
        if (existingByEmail.isEmpty()) {
            String hash = passwordEncoder.encode(adminPassword);
            jdbc.update("""
                    INSERT INTO users (id, normalized_email, display_name, password_hash, status, created_at)
                    VALUES (?, ?, ?, ?, 'ACTIVE', ?)
                    ON CONFLICT (normalized_email) DO NOTHING
                    """,
                    targetId,
                    adminEmail,
                    adminDisplayName,
                    hash,
                    Timestamp.from(clock.instant())
            );
            targetId = jdbc.queryForObject("""
                    SELECT id FROM users WHERE normalized_email = ?
                    """, UUID.class, adminEmail);
        }

        if (targetId != null && existingAdminUsers.isEmpty()) {
            jdbc.update("""
                    INSERT INTO user_roles (user_id, role)
                    VALUES (?, 'ADMIN')
                    ON CONFLICT DO NOTHING
                    """, targetId);
        }
    }
}
