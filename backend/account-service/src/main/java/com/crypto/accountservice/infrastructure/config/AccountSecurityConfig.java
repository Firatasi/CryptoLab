package com.crypto.accountservice.infrastructure.config;
import com.crypto.accountservice.api.dto.response.AccountError;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import java.util.Map;
import java.util.UUID;
@Configuration
public class AccountSecurityConfig {
    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper objectMapper() {
        var mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        return mapper;
    }

    @Bean JwtDecoder accountDecoder(@Value("${jwt.jwks-url}") String jwks,
            @Value("${jwt.issuer}") String issuer,@Value("${jwt.audience}") String audience) {
        var decoder=NimbusJwtDecoder.withJwkSetUri(jwks).jwsAlgorithm(SignatureAlgorithm.RS256).build();
        OAuth2TokenValidator<Jwt> claims=jwt -> {
            boolean valid=jwt.getExpiresAt()!=null && jwt.getAudience().contains(audience);
            try { valid=valid && UUID.fromString(jwt.getSubject()).toString().equalsIgnoreCase(jwt.getSubject()); }
            catch(RuntimeException invalidSubject) { valid=false; }
            return valid ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer),claims));
        return decoder;
    }
    @Bean SecurityFilterChain accountSecurity(HttpSecurity http,ObjectMapper json) throws Exception {
        // Account yalnız Authorization Bearer ile GET kabul eder. Cookie oturumu/login yok.
        // Bu ayarı Identity refresh/logout CSRF politikasına kopyalama.
        http.csrf(c -> c.disable()).sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(f -> f.disable()).httpBasic(b -> b.disable())
                .authorizeHttpRequests(a -> a.requestMatchers("/actuator/health").permitAll()
                    .requestMatchers(HttpMethod.GET,"/api/v1/account/me/summary","/api/v1/account/me/trades").authenticated()
                    .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(j -> {})
                    .authenticationEntryPoint((req,res,e) -> {
                        res.setStatus(401);res.setContentType("application/json");res.setHeader("Cache-Control","no-store");
                        res.setHeader("WWW-Authenticate","Bearer");
                        json.writeValue(res.getOutputStream(),new AccountError("UNAUTHORIZED","Oturum gerekli.",UUID.randomUUID().toString(),false,Map.of()));
                    }))
                .exceptionHandling(e -> e.accessDeniedHandler((req,res,ex) -> {
                    res.setStatus(403);res.setContentType("application/json");res.setHeader("Cache-Control","no-store");
                    json.writeValue(res.getOutputStream(),new AccountError("FORBIDDEN","İşleme izin verilmedi.",UUID.randomUUID().toString(),false,Map.of()));
                }));
        // Spring Security varsayılan cache headers korunur; kullanıcının raporu public cache'e verilmez.
        return http.build();
    }
}

