package com.crypto.identityservice.application.service;

import com.crypto.identityservice.api.dto.response.CaptchaChallengeResponse;
import com.crypto.identityservice.application.security.Crypto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

@Service
public class CaptchaService {

    // Karıştırılmaya müsait 0/O/1/I/l karakterleri dışarıda tutulur.
    private static final char[] ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private static final int CODE_LENGTH = 5;
    private static final int TTL_SECONDS = 300;
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final int MAX_ACTIVE_CHALLENGES = 20_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final TransactionTemplate requiresNewTx;

    public CaptchaService(
            JdbcTemplate jdbc,
            Clock clock,
            PlatformTransactionManager txManager
    ) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.requiresNewTx = new TransactionTemplate(txManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional
    public CaptchaChallengeResponse createChallenge() {
        Instant now = clock.instant();
        // Süresi dolmuş eski challenge kayıtlarını temiz tut.
        jdbc.update("""
                DELETE FROM captcha_challenges
                WHERE expires_at < ?
                """, Timestamp.from(now.minusSeconds(900)));

        Integer activeCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM captcha_challenges
                WHERE consumed_at IS NULL AND expires_at >= ?
                """, Integer.class, Timestamp.from(now));
        if (activeCount != null && activeCount >= MAX_ACTIVE_CHALLENGES) {
            jdbc.update("""
                    DELETE FROM captcha_challenges
                    WHERE id IN (
                        SELECT id FROM captcha_challenges
                        ORDER BY created_at ASC
                        LIMIT 1000
                    )
                    """);
        }

        String code = generateCode();
        UUID captchaId = UUID.randomUUID();
        return storeChallenge(captchaId, code, now);
    }

    /**
     * Deterministik test doğrulaması veya dahili senaryolar için belirli ID/kod ile challenge üretir.
     */
    @Transactional
    public CaptchaChallengeResponse createChallengeForTest(UUID captchaId, String code) {
        String normalized = normalize(code);
        if (normalized.isEmpty() || normalized.length() > 16 || !normalized.matches("^[A-Z0-9]+$")) {
            throw new IllegalArgumentException("CAPTCHA_INVALID");
        }
        return storeChallenge(captchaId, normalized, clock.instant());
    }

    private CaptchaChallengeResponse storeChallenge(UUID captchaId, String code, Instant now) {
        String answerHash = hashAnswer(captchaId, code);
        Instant expiresAt = now.plusSeconds(TTL_SECONDS);

        jdbc.update("""
                INSERT INTO captcha_challenges (id, answer_hash, failed_attempts, expires_at, created_at)
                VALUES (?, ?, 0, ?, ?)
                """, captchaId, answerHash, Timestamp.from(expiresAt), Timestamp.from(now));

        String svg = renderSvg(code);
        String dataUri = "data:image/svg+xml;base64,"
                + Base64.getEncoder().encodeToString(svg.getBytes(StandardCharsets.UTF_8));
        return new CaptchaChallengeResponse(captchaId, dataUri, TTL_SECONDS);
    }

    public void verifyAndConsume(UUID captchaId, String rawAnswer) {
        if (captchaId == null || rawAnswer == null || rawAnswer.isBlank()) {
            throw new IllegalArgumentException("CAPTCHA_INVALID");
        }

        Instant now = clock.instant();
        String outcome = requiresNewTx.execute(status -> {
            var rows = jdbc.query("""
                    SELECT answer_hash, failed_attempts, expires_at, consumed_at
                    FROM captcha_challenges
                    WHERE id = ?
                    FOR UPDATE
                    """,
                    (rs, rowNum) -> new ChallengeRow(
                            rs.getString("answer_hash"),
                            rs.getInt("failed_attempts"),
                            rs.getTimestamp("expires_at").toInstant(),
                            rs.getTimestamp("consumed_at") == null ? null : rs.getTimestamp("consumed_at").toInstant()
                    ),
                    captchaId
            );

            if (rows.isEmpty()) {
                return "CAPTCHA_EXPIRED";
            }

            ChallengeRow row = rows.getFirst();
            if (row.consumedAt() != null || !row.expiresAt().isAfter(now) || row.failedAttempts() >= MAX_FAILED_ATTEMPTS) {
                return "CAPTCHA_EXPIRED";
            }

            String candidateHash = hashAnswer(captchaId, normalize(rawAnswer));
            if (!row.answerHash().equals(candidateHash)) {
                jdbc.update("""
                        UPDATE captcha_challenges
                        SET failed_attempts = failed_attempts + 1,
                            consumed_at = CASE WHEN failed_attempts + 1 >= ? THEN ? ELSE consumed_at END
                        WHERE id = ? AND consumed_at IS NULL
                        """, MAX_FAILED_ATTEMPTS, Timestamp.from(now), captchaId);
                return "CAPTCHA_INVALID";
            }

            int updated = jdbc.update("""
                    UPDATE captcha_challenges
                    SET consumed_at = ?
                    WHERE id = ? AND consumed_at IS NULL
                    """, Timestamp.from(now), captchaId);

            return updated == 0 ? "CAPTCHA_EXPIRED" : "OK";
        });

        if (!"OK".equals(outcome)) {
            throw new IllegalArgumentException(outcome == null ? "CAPTCHA_EXPIRED" : outcome);
        }
    }

    private static String generateCode() {
        char[] out = new char[CODE_LENGTH];
        for (int i = 0; i < CODE_LENGTH; i++) {
            out[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        }
        return new String(out);
    }

    private static String normalize(String input) {
        return input.strip().replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    private static String hashAnswer(UUID captchaId, String normalizedCode) {
        return Crypto.hash(captchaId + ":" + normalizedCode);
    }

    private static String renderSvg(String code) {
        StringBuilder svg = new StringBuilder();
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"196\" height=\"56\" viewBox=\"0 0 196 56\">");
        svg.append("<rect width=\"196\" height=\"56\" fill=\"#f5f5f5\"/>");
        svg.append("<rect x=\"0.5\" y=\"0.5\" width=\"195\" height=\"55\" fill=\"none\" stroke=\"#d4d4d4\"/>");

        for (int i = 0; i < 4; i++) {
            int x1 = RANDOM.nextInt(40);
            int y1 = 8 + RANDOM.nextInt(40);
            int cx = 70 + RANDOM.nextInt(56);
            int cy = 4 + RANDOM.nextInt(48);
            int x2 = 156 + RANDOM.nextInt(38);
            int y2 = 8 + RANDOM.nextInt(40);
            String stroke = i % 2 == 0 ? "#a3a3a3" : "#737373";
            svg.append("<path d=\"M").append(x1).append(' ').append(y1)
                    .append(" Q").append(cx).append(' ').append(cy)
                    .append(' ').append(x2).append(' ').append(y2)
                    .append("\" fill=\"none\" stroke=\"").append(stroke)
                    .append("\" stroke-width=\"1.2\" stroke-opacity=\"0.65\"/>");
        }

        for (int i = 0; i < 24; i++) {
            int cx = 6 + RANDOM.nextInt(184);
            int cy = 6 + RANDOM.nextInt(44);
            int r = 1 + RANDOM.nextInt(2);
            svg.append("<circle cx=\"").append(cx).append("\" cy=\"").append(cy)
                    .append("\" r=\"").append(r).append("\" fill=\"#a3a3a3\" opacity=\"0.55\"/>");
        }

        for (int i = 0; i < code.length(); i++) {
            int x = 22 + i * 34 + RANDOM.nextInt(6) - 3;
            int y = 37 + RANDOM.nextInt(8) - 4;
            int angle = RANDOM.nextInt(29) - 14;
            char ch = code.charAt(i);
            svg.append("<text x=\"").append(x).append("\" y=\"").append(y)
                    .append("\" transform=\"rotate(").append(angle).append(' ').append(x).append(' ').append(y)
                    .append(")\" font-family=\"ui-monospace, SFMono-Regular, Menlo, Consolas, monospace\" ")
                    .append("font-size=\"26\" font-weight=\"700\" fill=\"#0a0a0a\" letter-spacing=\"1\">")
                    .append(ch)
                    .append("</text>");
        }

        svg.append("</svg>");
        return svg.toString();
    }

    private record ChallengeRow(
            String answerHash,
            int failedAttempts,
            Instant expiresAt,
            Instant consumedAt
    ) {
    }
}
