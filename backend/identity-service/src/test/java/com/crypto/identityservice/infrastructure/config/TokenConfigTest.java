package com.crypto.identityservice.infrastructure.config;

import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.NoSuchFileException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenConfigTest {

    private static final String MISSING_PRIVATE = "/keys/missing-ci-private.pem";
    private static final String MISSING_PUBLIC = "/keys/missing-ci-public.pem";
    private final TokenConfig config = new TokenConfig();

    @Test
    void rejectsMissingKeysWhenEphemeralFallbackDisabled() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("test");

        assertThrows(
                NoSuchFileException.class,
                () -> config.signingKey(MISSING_PRIVATE, MISSING_PUBLIC, "test-kid", false, env)
        );
    }

    @Test
    void rejectsMissingKeysWhenProdProfileIsActive() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("test", "prod");

        assertThrows(
                NoSuchFileException.class,
                () -> config.signingKey(MISSING_PRIVATE, MISSING_PUBLIC, "prod-kid", true, env)
        );
    }

    @Test
    void rejectsMissingKeysOutsideTestProfile() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev");

        assertThrows(
                NoSuchFileException.class,
                () -> config.signingKey(MISSING_PRIVATE, MISSING_PUBLIC, "dev-kid", true, env)
        );
    }

    @Test
    void generatesEphemeralRsaKeyWhenTestProfileAllowsFallbackAndPemFilesAreAbsent() throws Exception {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("test");

        RSAKey key = config.signingKey(MISSING_PRIVATE, MISSING_PUBLIC, "ci-kid", true, env);

        assertNotNull(key);
        assertEquals("ci-kid", key.getKeyID());
        assertTrue(key.isPrivate());
        assertEquals(2048, key.size());
        assertEquals(key.toRSAPublicKey().getModulus(), key.toRSAPrivateKey().getModulus());
    }

    @Test
    void rejectsBlankKeyPathsEvenInTestProfile() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("test");

        IllegalArgumentException privateBlankEx = assertThrows(
                IllegalArgumentException.class,
                () -> config.signingKey(" ", MISSING_PUBLIC, "ci-kid", true, env)
        );
        assertEquals("KEY_PATH_REQUIRED", privateBlankEx.getMessage());

        IllegalArgumentException publicBlankEx = assertThrows(
                IllegalArgumentException.class,
                () -> config.signingKey(MISSING_PRIVATE, " ", "ci-kid", true, env)
        );
        assertEquals("KEY_PATH_REQUIRED", publicBlankEx.getMessage());
    }
}
