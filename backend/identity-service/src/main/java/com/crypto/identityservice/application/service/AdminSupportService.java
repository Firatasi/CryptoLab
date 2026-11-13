package com.crypto.identityservice.application.service;

import com.crypto.identityservice.api.dto.request.ContactMessageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class AdminSupportService {

    public static final String SUPPORT_INBOX = "support@cryptolab.dev";
    private static final Set<String> ALLOWED_RECIPIENTS = Set.of(SUPPORT_INBOX, "research@cryptolab.dev");
    private static final Set<String> ALLOWED_STATUSES = Set.of("NEW", "READ", "RESOLVED");
    private static final long LIVE_WINDOW_SECONDS = 900;

    public record ContactSubmissionView(
            UUID id,
            String recipientEmail,
            String senderName,
            String senderEmail,
            String subject,
            String message,
            String status,
            Instant createdAt,
            Instant readAt
    ) {
    }

    public record AdminOverview(
            long liveUserCount,
            long activeSessionUsersCount,
            long totalUserCount,
            long newContactCount,
            long totalContactCount,
            String supportInbox,
            List<ContactSubmissionView> messages
    ) {
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AdminSupportService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public ContactSubmissionView submitContact(ContactMessageRequest request) {
        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        String recipient = normalizeRecipient(request.recipientEmail());
        String senderName = request.senderName().strip();
        String senderEmail = request.senderEmail().strip().toLowerCase(Locale.ROOT);
        String subject = request.subject().strip();
        String message = request.message().strip();

        jdbc.update("""
                INSERT INTO contact_messages (
                    id, recipient_email, sender_name, sender_email, subject, message, status, created_at
                )
                VALUES (?, ?, ?, ?, ?, ?, 'NEW', ?)
                """, id, recipient, senderName, senderEmail, subject, message, Timestamp.from(now));

        return new ContactSubmissionView(
                id,
                recipient,
                senderName,
                senderEmail,
                subject,
                message,
                "NEW",
                now,
                null
        );
    }

    @Transactional(readOnly = true)
    public AdminOverview overview() {
        Instant now = clock.instant();
        Instant activeCutoff = now.minusSeconds(LIVE_WINDOW_SECONDS);

        Long liveUsers = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT u.id)
                FROM users u
                JOIN refresh_sessions rs ON rs.user_id = u.id
                LEFT JOIN user_roles admin_role
                  ON admin_role.user_id = u.id AND admin_role.role = 'ADMIN'
                WHERE admin_role.user_id IS NULL
                  AND u.status = 'ACTIVE'
                  AND rs.revoked_at IS NULL
                  AND rs.expires_at > ?
                  AND u.last_seen_at IS NOT NULL
                  AND u.last_seen_at >= ?
                """, Long.class, Timestamp.from(now), Timestamp.from(activeCutoff));

        Long activeSessionUsers = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT u.id)
                FROM users u
                JOIN refresh_sessions rs ON rs.user_id = u.id
                LEFT JOIN user_roles admin_role
                  ON admin_role.user_id = u.id AND admin_role.role = 'ADMIN'
                WHERE admin_role.user_id IS NULL
                  AND u.status = 'ACTIVE'
                  AND rs.revoked_at IS NULL
                  AND rs.expires_at > ?
                """, Long.class, Timestamp.from(now));

        Long totalUsers = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM users u
                WHERE NOT EXISTS (
                    SELECT 1 FROM user_roles ur WHERE ur.user_id = u.id AND ur.role = 'ADMIN'
                )
                """, Long.class);

        Long newMessages = jdbc.queryForObject("""
                SELECT COUNT(*) FROM contact_messages WHERE status = 'NEW'
                """, Long.class);

        Long totalMessages = jdbc.queryForObject("""
                SELECT COUNT(*) FROM contact_messages
                """, Long.class);

        List<ContactSubmissionView> messages = jdbc.query("""
                SELECT id, recipient_email, sender_name, sender_email, subject, message, status, created_at, read_at
                FROM contact_messages
                ORDER BY created_at DESC
                LIMIT 200
                """,
                (rs, rowNum) -> {
                    Timestamp readAt = rs.getTimestamp("read_at");
                    return new ContactSubmissionView(
                            rs.getObject("id", UUID.class),
                            rs.getString("recipient_email"),
                            rs.getString("sender_name"),
                            rs.getString("sender_email"),
                            rs.getString("subject"),
                            rs.getString("message"),
                            rs.getString("status"),
                            rs.getTimestamp("created_at").toInstant(),
                            readAt == null ? null : readAt.toInstant()
                    );
                });

        return new AdminOverview(
                liveUsers == null ? 0L : liveUsers,
                activeSessionUsers == null ? 0L : activeSessionUsers,
                totalUsers == null ? 0L : totalUsers,
                newMessages == null ? 0L : newMessages,
                totalMessages == null ? 0L : totalMessages,
                SUPPORT_INBOX,
                messages
        );
    }

    @Transactional
    public ContactSubmissionView updateStatus(UUID messageId, String status) {
        if (status == null || !ALLOWED_STATUSES.contains(status)) {
            throw new IllegalArgumentException("INVALID_REQUEST");
        }
        Instant now = clock.instant();
        int updated = jdbc.update("""
                UPDATE contact_messages
                SET status = ?,
                    read_at = CASE WHEN ? IN ('READ', 'RESOLVED') AND read_at IS NULL THEN ? ELSE read_at END
                WHERE id = ?
                """, status, status, Timestamp.from(now), messageId);
        if (updated == 0) {
            throw new org.springframework.dao.EmptyResultDataAccessException(1);
        }

        return jdbc.queryForObject("""
                SELECT id, recipient_email, sender_name, sender_email, subject, message, status, created_at, read_at
                FROM contact_messages
                WHERE id = ?
                """,
                (rs, rowNum) -> {
                    Timestamp readAt = rs.getTimestamp("read_at");
                    return new ContactSubmissionView(
                            rs.getObject("id", UUID.class),
                            rs.getString("recipient_email"),
                            rs.getString("sender_name"),
                            rs.getString("sender_email"),
                            rs.getString("subject"),
                            rs.getString("message"),
                            rs.getString("status"),
                            rs.getTimestamp("created_at").toInstant(),
                            readAt == null ? null : readAt.toInstant()
                    );
                }, messageId);
    }

    private static String normalizeRecipient(String raw) {
        if (raw == null || raw.isBlank()) {
            return SUPPORT_INBOX;
        }
        String normalized = raw.strip().toLowerCase(Locale.ROOT);
        return ALLOWED_RECIPIENTS.contains(normalized) ? normalized : SUPPORT_INBOX;
    }
}
