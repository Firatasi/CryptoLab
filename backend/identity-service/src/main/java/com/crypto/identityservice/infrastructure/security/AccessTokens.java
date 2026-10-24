package com.crypto.identityservice.infrastructure.security;

import com.crypto.identityservice.application.port.TokenIssuer;
import com.crypto.identityservice.application.repository.IdentityRepository;
import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

@Service
public class AccessTokens implements TokenIssuer {

    private final JwtEncoder encoder;
    private final RSAKey key;
    private final IdentityRepository users;
    private final Clock clock;
    private final String issuer;
    private final String audience;

    public AccessTokens(
            JwtEncoder encoder,
            RSAKey key,
            IdentityRepository users,
            Clock clock,
            @Value("${jwt.issuer}") String issuer,
            @Value("${jwt.audience}") String audience
    ) {
        this.encoder = encoder;
        this.key = key;
        this.users = users;
        this.clock = clock;
        this.issuer = issuer;
        this.audience = audience;
    }

    @Override
    public String issue(UUID user) {
        var roles = users.roles(user);
        var authorities = users.authorities(user);
        var now = clock.instant();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(user.toString())
                .audience(List.of(audience))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .id(UUID.randomUUID().toString())
                .claim("roles", roles)
                .claim("authorities", authorities)
                .build();

        JwsHeader header = JwsHeader
                .with(SignatureAlgorithm.RS256)
                .keyId(key.getKeyID())
                .build();

        return encoder.encode(
                JwtEncoderParameters.from(header, claims)
        ).getTokenValue();
    }
}