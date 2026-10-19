package com.crypto.identityservice.infrastructure.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtValidationTest {

    private static final String ISSUER = "http://identity.test";
    private static final String AUDIENCE = "cryptolab-api";
    private static final String KEY_ID = "jwt-test-key";
    private static final String SUBJECT = UUID.randomUUID().toString();

    private static HttpServer jwksServer;
    private static RSAKey signingKey;
    private static JwtDecoder decoder;

    @BeforeAll
    static void setUp() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var keyPair = generator.generateKeyPair();

        signingKey = new RSAKey.Builder(
                (RSAPublicKey) keyPair.getPublic()
        )
                .privateKey((RSAPrivateKey) keyPair.getPrivate())
                .keyID(KEY_ID)
                .build();

        byte[] jwks = new JWKSet(signingKey.toPublicJWK())
                .toString()
                .getBytes(StandardCharsets.UTF_8);

        jwksServer = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0),
                0
        );

        jwksServer.createContext("/jwks", exchange -> {
            try {
                exchange.getResponseHeaders().set(
                        "Content-Type",
                        "application/json"
                );
                exchange.sendResponseHeaders(200, jwks.length);

                try (var output = exchange.getResponseBody()) {
                    output.write(jwks);
                }
            } finally {
                exchange.close();
            }
        });

        jwksServer.start();

        String jwksUrl = "http://127.0.0.1:"
                + jwksServer.getAddress().getPort()
                + "/jwks";

        decoder = new SecurityConfig().decoder(
                jwksUrl,
                ISSUER,
                AUDIENCE
        );
    }

    @AfterAll
    static void tearDown() {
        if (jwksServer != null) {
            jwksServer.stop(0);
        }
    }

    @Test
    void shouldAcceptValidToken() throws Exception {
        String token = sign(validClaims().build());

        var jwt = decoder.decode(token);

        assertEquals(SUBJECT, jwt.getSubject());
        assertEquals(List.of(AUDIENCE), jwt.getAudience());
        assertEquals(
                List.of("USER"),
                jwt.getClaimAsStringList("roles")
        );
    }

    @Test
    void shouldRejectWrongIssuer() throws Exception {
        String token = sign(
                validClaims()
                        .issuer("http://untrusted.test")
                        .build()
        );

        assertRejected(token);
    }

    @Test
    void shouldRejectWrongAudience() throws Exception {
        String token = sign(
                validClaims()
                        .audience("another-api")
                        .build()
        );

        assertRejected(token);
    }

    @Test
    void shouldRejectExpiredToken() throws Exception {
        Instant now = Instant.now();

        String token = sign(
                validClaims()
                        .issueTime(Date.from(now.minusSeconds(600)))
                        .expirationTime(Date.from(now.minusSeconds(180)))
                        .build()
        );

        assertRejected(token);
    }

    @Test
    void shouldRejectMissingExpiration() throws Exception {
        String token = sign(
                validClaims()
                        .expirationTime(null)
                        .build()
        );

        assertRejected(token);
    }

    @Test
    void shouldRejectMissingSubject() throws Exception {
        String token = sign(
                validClaims()
                        .subject(null)
                        .build()
        );

        assertRejected(token);
    }

    @Test
    void shouldRejectNonUuidSubject() throws Exception {
        String token = sign(
                validClaims()
                        .subject("not-a-uuid")
                        .build()
        );

        assertRejected(token);
    }

    @Test
    void shouldRejectNonCanonicalUuidSubject() throws Exception {
        String token = sign(
                validClaims()
                        .subject("1-1-1-1-1")
                        .build()
        );

        assertRejected(token);
    }

    @Test
    void shouldRejectTamperedSignature() throws Exception {
        String token = sign(validClaims().build());
        String[] parts = token.split("\\.");

        char replacement = parts[2].charAt(0) == 'A' ? 'B' : 'A';
        parts[2] = replacement + parts[2].substring(1);

        assertRejected(String.join(".", parts));
    }

    @Test
    void shouldRejectUnknownKeyId() throws Exception {
        String token = sign(
                validClaims().build(),
                JWSAlgorithm.RS256,
                "unknown-key"
        );

        assertRejected(token);
    }

    @Test
    void shouldRejectRs512Algorithm() throws Exception {
        String token = sign(
                validClaims().build(),
                JWSAlgorithm.RS512,
                KEY_ID
        );

        assertRejected(token);
    }

    @Test
    void shouldRejectRolesWithInvalidType() throws Exception {
        String token = sign(
                validClaims()
                        .claim("roles", "ADMIN")
                        .build()
        );

        assertRejected(token);
    }

    @Test
    void shouldRejectAuthoritiesWithInvalidType() throws Exception {
        String token = sign(
                validClaims()
                        .claim("authorities", List.of(123))
                        .build()
        );

        assertRejected(token);
    }

    private static JWTClaimsSet.Builder validClaims() {
        Instant now = Instant.now();

        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .subject(SUBJECT)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .jwtID(UUID.randomUUID().toString())
                .claim("roles", List.of("USER"))
                .claim("authorities", List.of());
    }

    private static String sign(JWTClaimsSet claims) throws Exception {
        return sign(claims, JWSAlgorithm.RS256, KEY_ID);
    }

    private static String sign(
            JWTClaimsSet claims,
            JWSAlgorithm algorithm,
            String keyId
    ) throws Exception {
        var header = new JWSHeader.Builder(algorithm)
                .keyID(keyId)
                .build();

        var jwt = new SignedJWT(header, claims);
        jwt.sign(new RSASSASigner(signingKey));

        return jwt.serialize();
    }

    private static void assertRejected(String token) {
        assertThrows(
                JwtException.class,
                () -> decoder.decode(token)
        );
    }
}