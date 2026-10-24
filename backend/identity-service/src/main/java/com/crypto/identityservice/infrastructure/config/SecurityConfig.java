package com.crypto.identityservice.infrastructure.config;

import com.crypto.identityservice.api.exception.ErrorResponses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Configuration
@EnableMethodSecurity
@ConditionalOnWebApplication(
        type = ConditionalOnWebApplication.Type.SERVLET
)
public class SecurityConfig {

    @Bean
    public JwtDecoder decoder(
            @Value("${jwt.jwks-url}") String jwksUrl,
            @Value("${jwt.issuer}") String issuer,
            @Value("${jwt.audience}") String audience
    ) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withJwkSetUri(jwksUrl)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();

        OAuth2TokenValidator<Jwt> requiredClaims = jwt -> {
            try {
                String subject = jwt.getSubject();

                boolean valid =
                        jwt.getExpiresAt() != null
                                && jwt.getAudience() != null
                                && jwt.getAudience().contains(audience)
                                && subject != null
                                && UUID.fromString(subject)
                                .toString()
                                .equalsIgnoreCase(subject)
                                && isStringList(jwt.getClaims().get("roles"))
                                && isStringList(
                                jwt.getClaims().get("authorities")
                        );

                if (valid) {
                    return OAuth2TokenValidatorResult.success();
                }
            } catch (RuntimeException ignored) {
                // Bozuk claim, kimlik doğrulamasını başarısız yapar.
            }

            return OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token")
            );
        };

        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(
                        JwtValidators.createDefaultWithIssuer(issuer),
                        requiredClaims
                )
        );

        return decoder;
    }

    private static boolean isStringList(Object value) {
        return value == null
                || value instanceof List<?> list
                && list.stream().allMatch(String.class::isInstance);
    }

    @Bean
    public SecurityFilterChain security(HttpSecurity http) throws Exception {
        JwtAuthenticationConverter converter =
                new JwtAuthenticationConverter();

        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            var granted = new ArrayList<GrantedAuthority>();

            List<String> roles = jwt.getClaimAsStringList("roles");

            if (roles != null) {
                roles.stream()
                        .filter(Set.of("USER", "TRAINER", "ADMIN")::contains)
                        .forEach(role -> granted.add(
                                new SimpleGrantedAuthority("ROLE_" + role)
                        ));
            }

            List<String> authorities =
                    jwt.getClaimAsStringList("authorities");

            if (authorities != null) {
                authorities.stream()
                        .filter("MODEL_PUBLISH"::equals)
                        .forEach(authority -> granted.add(
                                new SimpleGrantedAuthority(authority)
                        ));
            }

            return granted;
        });

        AuthenticationEntryPoint unauthorized = (request, response, exception) -> {
            response.setHeader("WWW-Authenticate", "Bearer");

            ErrorResponses.write(
                    response,
                    401,
                    "UNAUTHORIZED"
            );
        };

        AccessDeniedHandler forbidden = (request, response, exception) ->
                ErrorResponses.write(
                        response,
                        403,
                        "FORBIDDEN"
                );

        return http
                // Cookie kullanan refresh/logout için controller'da
                // Origin + JSON + X-CSRF kontrolü uygulanıyor.
                .csrf(csrf -> csrf.disable())

                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )

                .requestCache(cache -> cache.disable())

                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/actuator/health",
                                "/.well-known/jwks.json",
                                "/api/v1/auth/register",
                                "/api/v1/auth/login",
                                "/api/v1/auth/refresh",
                                "/api/v1/auth/logout"
                        ).permitAll()

                        .requestMatchers("/internal/**").denyAll()

                        .anyRequest().authenticated()
                )

                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(forbidden)
                )

                .oauth2ResourceServer(resourceServer -> resourceServer
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(forbidden)
                        .jwt(jwt -> jwt
                                .jwtAuthenticationConverter(converter)
                        )
                )

                .build();
    }
}