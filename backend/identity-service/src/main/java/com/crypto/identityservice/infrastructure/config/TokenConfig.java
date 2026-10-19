package com.crypto.identityservice.infrastructure.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;

@Configuration
public class TokenConfig {

    private static byte[] readPem(String file) throws Exception {
        String content = Files.readString(Path.of(file))
                .replaceAll("-----[^-]+-----", "")
                .replaceAll("\\s", "");

        return Base64.getDecoder().decode(content);
    }

    @Bean
    public RSAKey signingKey(
            @Value("${jwt.private-key-path}") String privatePath,
            @Value("${jwt.public-key-path}") String publicPath,
            @Value("${jwt.kid}") String kid
    ) throws Exception {
        KeyFactory factory = KeyFactory.getInstance("RSA");

        RSAPublicKey publicKey = (RSAPublicKey) factory.generatePublic(
                new X509EncodedKeySpec(readPem(publicPath))
        );

        RSAPrivateKey privateKey = (RSAPrivateKey) factory.generatePrivate(
                new PKCS8EncodedKeySpec(readPem(privatePath))
        );

        if (!publicKey.getModulus().equals(privateKey.getModulus())) {
            throw new IllegalStateException("KEY_PAIR_MISMATCH");
        }

        return new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyID(kid)
                .build();
    }

    @Bean
    public JwtEncoder encoder(RSAKey key) {
        return new NimbusJwtEncoder(
                new ImmutableJWKSet<>(new JWKSet(key))
        );
    }

    @Bean
    public PasswordEncoder passwords() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}