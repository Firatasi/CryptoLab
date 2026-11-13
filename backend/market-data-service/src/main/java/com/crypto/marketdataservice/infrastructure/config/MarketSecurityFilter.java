package com.crypto.marketdataservice.infrastructure.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

/**
 * Piyasa servisi uçlarına temel HTTP güvenlik başlıklarını uygular ve iç uçları reddeder.
 */
@Component
public class MarketSecurityFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE_READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        response.setHeader("Cross-Origin-Resource-Policy", "same-site");

        String uri = request.getRequestURI();
        if (uri != null && (uri.equals("/internal") || uri.startsWith("/internal/"))) {
            writeError(response, 403, "FORBIDDEN", "Bu işlem için izin yok.");
            return;
        }
        if (uri != null && uri.startsWith("/api/v1/market/")
                && !SAFE_READ_METHODS.contains(request.getMethod())) {
            writeError(response, 405, "METHOD_NOT_ALLOWED", "Bu HTTP yöntemi desteklenmiyor.");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static void writeError(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        String body = "{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"traceId\":\""
                + UUID.randomUUID() + "\",\"retryable\":false,\"details\":{}}";
        response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
    }
}
