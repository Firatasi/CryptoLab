package com.crypto.identityservice.application.service;

import com.crypto.identityservice.application.command.RegisterCommand;
import com.crypto.identityservice.application.port.LoginLimiter;
import com.crypto.identityservice.application.port.TokenIssuer;
import com.crypto.identityservice.application.repository.IdentityRepository;
import com.crypto.identityservice.application.security.Crypto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

@Service
public class IdentityService {

    public record Issued(
            String accessToken,
            String refreshToken,
            long expiresIn
    ) {
        @Override
        public String toString() {
            return "Issued[REDACTED]";
        }
    }

    public record RefreshResult(
            Issued issued,
            String error
    ) {
    }

    private final IdentityRepository users;
    private final PasswordEncoder passwords;
    private final TokenIssuer accessTokens;
    private final LoginLimiter limiter;
    private final Clock clock;
    private final String dummyHash;
    private final byte[] registrationHashKey;

    public IdentityService(
            IdentityRepository users,
            PasswordEncoder passwords,
            TokenIssuer accessTokens,
            LoginLimiter limiter,
            Clock clock,
            @Value("${registration.hash-key}") String registrationHashKey
    ) {
        this.users = users;
        this.passwords = passwords;
        this.accessTokens = accessTokens;
        this.limiter = limiter;
        this.clock = clock;

        // Olmayan kullanıcıda da BCrypt doğrulaması yapabilmek için.
        this.dummyHash = passwords.encode(Crypto.randomToken());

        this.registrationHashKey = Base64.getDecoder()
                .decode(registrationHashKey);

        if (this.registrationHashKey.length < 32) {
            throw new IllegalArgumentException(
                    "REGISTRATION_HASH_KEY_TOO_SHORT"
            );
        }
    }

    @Transactional
    public UUID register(RegisterCommand command, String key) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new IllegalArgumentException("INVALID_KEY");
        }

        String email = command.email()
                .strip()
                .toLowerCase(Locale.ROOT);

        String displayName = command.displayName().strip();
        String password = command.password();

        // BCrypt sınırı karakter değil, UTF-8 byte sayısıdır.
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("PASSWORD_TOO_LONG");
        }

        // Uzunluk önekleri alanların birleşimindeki belirsizliği önler.
        String canonicalRequest =
                email.length() + ":" + email
                        + displayName.length() + ":" + displayName
                        + password.length() + ":" + password;

        String digest = Crypto.requestHash(
                canonicalRequest,
                registrationHashKey
        );

        // Aynı idempotency anahtarıyla eşzamanlı kayıtları sıraya koy.
        users.lock("register:" + key);

        var previous = users.registration(key);

        if (previous.isPresent()) {
            if (!previous.get().hash().equals(digest)) {
                throw new IllegalStateException("IDEMPOTENCY_CONFLICT");
            }

            return previous.get().userId();
        }

        UUID userId = UUID.randomUUID();

        users.register(
                userId,
                email,
                displayName,
                passwords.encode(password),
                clock.instant(),
                key,
                digest
        );

        return userId;
    }

    @Transactional
    public Issued login(
            String email,
            String password,
            String clientIp
    ) {
        if (email == null || password == null) {
            throw new IllegalArgumentException("INVALID_CREDENTIALS");
        }

        String normalizedEmail = email.strip()
                .toLowerCase(Locale.ROOT);

        // Hem IP hem kullanıcı hesabı bazında sınır uygula.
        limiter.check("ip:" + clientIp);
        limiter.check("email:" + Crypto.hash(normalizedEmail));

        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("INVALID_CREDENTIALS");
        }

        var credentials = users.credentials(normalizedEmail);

        String storedHash = credentials.isPresent()
                ? credentials.get().passwordHash()
                : dummyHash;

        // Kullanıcı bulunmasa da BCrypt çalışır.
        boolean valid = passwords.matches(password, storedHash);

        if (!valid
                || credentials.isEmpty()
                || !"ACTIVE".equals(credentials.get().status())) {
            throw new IllegalArgumentException("INVALID_CREDENTIALS");
        }

        return issue(
                credentials.get().id(),
                UUID.randomUUID()
        );
    }

    private Issued issue(UUID userId, UUID familyId) {
        String rawRefreshToken = Crypto.randomToken();

        users.insertRefresh(
                UUID.randomUUID(),
                userId,
                Crypto.hash(rawRefreshToken),
                familyId,
                clock.instant().plusSeconds(604_800)
        );

        return new Issued(
                accessTokens.issue(userId),
                rawRefreshToken,
                300
        );
    }

    @Transactional
    public RefreshResult rotate(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.length() != 43) {
            return new RefreshResult(null, "INVALID_REFRESH");
        }

        String hash = Crypto.hash(rawRefreshToken);
        var family = users.family(hash);

        if (family.isEmpty()) {
            return new RefreshResult(null, "INVALID_REFRESH");
        }

        // Aynı ailenin refresh ve logout işlemleri aynı kilidi kullanır.
        users.lock("refresh:" + family.get());

        var previous = users.lockRefresh(hash);
        var now = clock.instant();

        if (previous.revokedAt() != null) {
            users.revokeFamily(previous.familyId(), now);

            // Exception atma: aile iptalinin commit olması gerekiyor.
            return new RefreshResult(null, "REFRESH_REPLAY");
        }

        if (!previous.expiresAt().isAfter(now)) {
            return new RefreshResult(null, "EXPIRED_REFRESH");
        }

        if (!"ACTIVE".equals(users.status(previous.userId()))) {
            return new RefreshResult(null, "INVALID_REFRESH");
        }

        Issued next = issue(
                previous.userId(),
                previous.familyId()
        );

        UUID nextRefreshId = users.refreshId(
                Crypto.hash(next.refreshToken())
        );

        users.replaceRefresh(
                previous.id(),
                nextRefreshId,
                now
        );

        return new RefreshResult(next, null);
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.length() != 43) {
            return;
        }

        var family = users.family(
                Crypto.hash(rawRefreshToken)
        );

        if (family.isEmpty()) {
            return;
        }

        users.lock("refresh:" + family.get());

        users.revokeFamily(
                family.get(),
                clock.instant()
        );
    }
}