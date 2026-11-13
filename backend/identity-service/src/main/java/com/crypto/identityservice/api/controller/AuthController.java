package com.crypto.identityservice.api.controller;

import com.crypto.identityservice.api.dto.request.ContactMessageRequest;
import com.crypto.identityservice.api.dto.request.LoginRequest;
import com.crypto.identityservice.api.dto.request.RegisterRequest;
import com.crypto.identityservice.api.dto.response.CaptchaChallengeResponse;
import com.crypto.identityservice.api.dto.response.TokenResponse;
import com.crypto.identityservice.api.dto.response.UserResponse;
import com.crypto.identityservice.api.mapper.AuthMapper;
import com.crypto.identityservice.application.port.LoginLimiter;
import com.crypto.identityservice.application.service.AdminSupportService;
import com.crypto.identityservice.application.service.AdminSupportService.ContactSubmissionView;
import com.crypto.identityservice.application.service.CaptchaService;
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
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@RestController
public class AuthController {

    private static final String AUTH_PATH = "/api/v1/auth";
    private static final long REFRESH_TTL_SECONDS = 604_800;
    private static final Set<String> LOOPBACK_ADDRS = Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1");
    private static final Pattern SAFE_IP_PATTERN = Pattern.compile("^[0-9a-fA-F:.]{3,45}$");

    private final IdentityService service;
    private final UserQueryService users;
    private final CaptchaService captchaService;
    private final AdminSupportService supportService;
    private final LoginLimiter limiter;
    private final AuthMapper mapper;
    private final RSAKey signingKey;
    private final String allowedOrigin;
    private final boolean secureCookie;

    public AuthController(
            IdentityService service,
            UserQueryService users,
            CaptchaService captchaService,
            AdminSupportService supportService,
            LoginLimiter limiter,
            AuthMapper mapper,
            RSAKey signingKey,
            @Value("${web.origin}") String allowedOrigin,
            @Value("${cookie.secure:true}") boolean secureCookie
    ) {
        this.service = service;
        this.users = users;
        this.captchaService = captchaService;
        this.supportService = supportService;
        this.limiter = limiter;
        this.mapper = mapper;
        this.signingKey = signingKey;
        this.allowedOrigin = allowedOrigin;
        this.secureCookie = secureCookie;
    }

    @GetMapping(AUTH_PATH + "/captcha")
    public ResponseEntity<CaptchaChallengeResponse> captcha(HttpServletRequest httpRequest) {
        limiter.check("captcha-ip:" + resolveClientIp(httpRequest));
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(captchaService.createChallenge());
    }

    @PostMapping(
            value = AUTH_PATH + "/contact",
            consumes = "application/json"
    )
    public ResponseEntity<ContactSubmissionView> submitContact(
            @Valid @RequestBody ContactMessageRequest request,
            HttpServletRequest httpRequest
    ) {
        limiter.check("contact-ip:" + resolveClientIp(httpRequest));
        return ResponseEntity.status(201)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(supportService.submitContact(request));
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
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            limiter.check("register-ip:" + resolveClientIp(attrs.getRequest()));
        }

        UUID userId = service.register(
                mapper.toCommand(request),
                key
        );

        return ResponseEntity.status(201)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
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
        var issued = service.login(
                request.email(),
                request.password(),
                resolveClientIp(httpRequest)
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

        return mapper.toResponse(users.findAndTouch(subject));
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

    private static String resolveClientIp(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String remoteAddr = request.getRemoteAddr();
        if (remoteAddr != null && LOOPBACK_ADDRS.contains(remoteAddr)) {
            String realIp = normalizeIp(request.getHeader("X-Real-IP"));
            if (realIp != null) {
                return realIp;
            }
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                String first = normalizeIp(xff.split(",")[0]);
                if (first != null) {
                    return first;
                }
            }
        }
        String normalizedRemote = normalizeIp(remoteAddr);
        return normalizedRemote != null ? normalizedRemote : "unknown";
    }

    private static String normalizeIp(String candidate) {
        if (candidate == null) {
            return null;
        }
        String trimmed = candidate.trim();
        return SAFE_IP_PATTERN.matcher(trimmed).matches() ? trimmed : null;
    }
}