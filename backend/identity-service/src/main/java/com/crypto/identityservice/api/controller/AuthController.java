package com.crypto.identityservice.api.controller;

import com.crypto.identityservice.api.dto.request.LoginRequest;
import com.crypto.identityservice.api.dto.request.RegisterRequest;
import com.crypto.identityservice.api.dto.response.TokenResponse;
import com.crypto.identityservice.api.dto.response.UserResponse;
import com.crypto.identityservice.api.mapper.AuthMapper;
import com.crypto.identityservice.application.service.IdentityService;
import com.crypto.identityservice.application.service.UserQueryService;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
public class AuthController {

    private static final String AUTH_PATH = "/api/v1/auth";
    private static final long REFRESH_TTL_SECONDS = 604_800;

    private final IdentityService service;
    private final UserQueryService users;
    private final AuthMapper mapper;
    private final RSAKey signingKey;
    private final String allowedOrigin;
    private final boolean secureCookie;

    public AuthController(
            IdentityService service,
            UserQueryService users,
            AuthMapper mapper,
            RSAKey signingKey,
            @Value("${web.origin}") String allowedOrigin,
            @Value("${cookie.secure:true}") boolean secureCookie
    ) {
        this.service = service;
        this.users = users;
        this.mapper = mapper;
        this.signingKey = signingKey;
        this.allowedOrigin = allowedOrigin;
        this.secureCookie = secureCookie;
    }

    @PostMapping(
            value = AUTH_PATH + "/register",
            consumes = "application/json"
    )
    public ResponseEntity<UserResponse> register(
            @Valid @RequestBody RegisterRequest request,
            @RequestHeader("Idempotency-Key") String key
    ) {
        validateKey(key);

        UUID userId = service.register(
                mapper.toCommand(request),
                key
        );

        return ResponseEntity.status(201)
                .body(userView(userId));
    }

    @PostMapping(
            value = AUTH_PATH + "/login",
            consumes = "application/json"
    )
    public ResponseEntity<TokenResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest
    ) {
        // İstemcinin gönderdiği X-Forwarded-For değerine güvenmiyoruz.
        var issued = service.login(
                request.email(),
                request.password(),
                httpRequest.getRemoteAddr()
        );

        return tokenResponse(issued);
    }

    @PostMapping(
            value = AUTH_PATH + "/refresh",
            consumes = "application/json"
    )
    public ResponseEntity<?> refresh(
            @CookieValue(value = "refresh", required = false)
            String refreshToken,

            @RequestHeader(value = "Origin", required = false)
            String origin,

            @RequestHeader(value = "X-CSRF", required = false)
            String csrf
    ) {
        checkCsrf(origin, csrf);

        var result = service.rotate(refreshToken);

        // Servis proxy'si döndüğünde rotate transaction'ı commit olmuştur.
        if (result.error() != null) {
            return ResponseEntity.status(401)
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .header(
                            HttpHeaders.SET_COOKIE,
                            refreshCookie("", 0).toString()
                    )
                    .body(Map.of(
                            "code", result.error(),
                            "message", "Oturumu yenileyin.",
                            "traceId", UUID.randomUUID().toString(),
                            "retryable", false,
                            "details", Map.of()
                    ));
        }

        return tokenResponse(result.issued());
    }

    @PostMapping(
            value = AUTH_PATH + "/logout",
            consumes = "application/json"
    )
    public ResponseEntity<Void> logout(
            @CookieValue(value = "refresh", required = false)
            String refreshToken,

            @RequestHeader(value = "Origin", required = false)
            String origin,

            @RequestHeader(value = "X-CSRF", required = false)
            String csrf,

            @RequestHeader("Idempotency-Key")
            String key
    ) {
        checkCsrf(origin, csrf);
        validateKey(key);

        service.logout(refreshToken);

        return ResponseEntity.noContent()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(
                        HttpHeaders.SET_COOKIE,
                        refreshCookie("", 0).toString()
                )
                .build();
    }

    @GetMapping(AUTH_PATH + "/me")
    public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        UUID subject = UUID.fromString(jwt.getSubject());

        return userView(subject);
    }

    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        // Yalnız public key yayımlanır.
        return new JWKSet(
                signingKey.toPublicJWK()
        ).toJSONObject();
    }

    private UserResponse userView(UUID userId) {
        return mapper.toResponse(users.find(userId));
    }

    private ResponseEntity<TokenResponse> tokenResponse(
            IdentityService.Issued issued
    ) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(
                        HttpHeaders.SET_COOKIE,
                        refreshCookie(
                                issued.refreshToken(),
                                REFRESH_TTL_SECONDS
                        ).toString()
                )
                .body(new TokenResponse(
                        issued.accessToken(),
                        issued.expiresIn()
                ));
    }

    private ResponseCookie refreshCookie(
            String value,
            long maxAgeSeconds
    ) {
        return ResponseCookie.from("refresh", value)
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite("Strict")
                .path(AUTH_PATH)
                .maxAge(maxAgeSeconds)
                .build();
    }

    private void checkCsrf(String origin, String header) {
        if (!allowedOrigin.equals(origin) || !"1".equals(header)) {
            throw new AccessDeniedException("CSRF_REJECTED");
        }
    }

    private void validateKey(String key) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new IllegalArgumentException("INVALID_KEY");
        }
    }
}