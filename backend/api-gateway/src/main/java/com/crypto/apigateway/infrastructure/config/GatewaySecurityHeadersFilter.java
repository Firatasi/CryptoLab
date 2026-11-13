package com.crypto.apigateway.infrastructure.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Ağ geçidinden çıkan tüm yanıtlara standart güvenlik başlıklarını uygular.
 */
@Component
public class GatewaySecurityHeadersFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        applySecurityHeaders(request, response);
        filterChain.doFilter(request, response);
    }

    public static void applySecurityHeaders(HttpServletRequest request, HttpServletResponse response) {
        if (!response.containsHeader("X-Content-Type-Options")) {
            response.setHeader("X-Content-Type-Options", "nosniff");
        }
        if (!response.containsHeader("X-Frame-Options")) {
            response.setHeader("X-Frame-Options", "DENY");
        }
        if (!response.containsHeader("Referrer-Policy")) {
            response.setHeader("Referrer-Policy", "no-referrer");
        }
        if (!response.containsHeader("Permissions-Policy")) {
            response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=(), usb=()");
        }
        if (!response.containsHeader("Cross-Origin-Resource-Policy")) {
            response.setHeader("Cross-Origin-Resource-Policy", "same-site");
        }
        if (!response.containsHeader("X-XSS-Protection")) {
            response.setHeader("X-XSS-Protection", "0");
        }
        if (!response.containsHeader("Content-Security-Policy")) {
            response.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'; base-uri 'none'");
        }
        String proto = request.getHeader("X-Forwarded-Proto");
        if ((request.isSecure() || "https".equalsIgnoreCase(proto)) && !response.containsHeader("Strict-Transport-Security")) {
            response.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        }
    }
}
