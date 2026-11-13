package com.crypto.apigateway;

import com.crypto.apigateway.api.controller.ProxyController;
import com.crypto.apigateway.infrastructure.client.GatewayTargetResolver;
import com.crypto.apigateway.infrastructure.config.GatewayRateLimiter;
import com.crypto.apigateway.infrastructure.config.GatewaySecurityHeadersFilter;
import com.crypto.apigateway.infrastructure.config.RouteTable;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

class GatewayB08Test {

    @Test
    void routeTable_resolvesAllowedServicesAndRejectsInternalOrTraversalPaths() {
        assertEquals("identity-service", RouteTable.serviceId("/.well-known/jwks.json"));
        assertEquals("identity-service", RouteTable.serviceId("/api/v1/auth/login"));
        assertEquals("market-data-service", RouteTable.serviceId("/api/v1/market/instruments"));
        assertEquals("simulation-service", RouteTable.serviceId("/api/v1/sim/sessions"));
        assertEquals("strategy-service", RouteTable.serviceId("/api/v1/strategy/proposals"));
        assertEquals("reporting-service", RouteTable.serviceId("/api/v1/reports/sessions"));
        assertEquals("account-service", RouteTable.serviceId("/api/v1/account/me/summary"));

        assertThrows(IllegalArgumentException.class, () -> RouteTable.serviceId("/internal/export"));
        assertThrows(IllegalArgumentException.class, () -> RouteTable.serviceId("/api/v1/sim/../internal"));
        assertThrows(IllegalArgumentException.class, () -> RouteTable.serviceId("/api/v1/auth;/../internal"));
        assertThrows(IllegalArgumentException.class, () -> RouteTable.serviceId("/api/v1/sim//sessions"));
        assertThrows(IllegalArgumentException.class, () -> RouteTable.serviceId("/api/v1/unknown/path"));
    }

    @Test
    void proxyController_rejectsTraceAndEnforcesRateLimitAndSecurityHeaders() throws Exception {
        GatewayRateLimiter limiter = new GatewayRateLimiter(true, 10, 30);
        ProxyController controller = new ProxyController(
                new GatewayTargetResolver(null),
                limiter,
                false,
                ""
        );

        MockHttpServletRequest traceReq = new MockHttpServletRequest("TRACE", "/api/v1/auth/login");
        MockHttpServletResponse traceRes = new MockHttpServletResponse();
        controller.proxy(traceReq, traceRes);
        assertEquals(405, traceRes.getStatus());
        assertTrue(traceRes.getContentAsString().contains("METHOD_NOT_ALLOWED"));

        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.allow("198.51.100.20", "/api/v1/auth/login"));
        }
        assertFalse(limiter.allow("198.51.100.20", "/api/v1/auth/login"));

        MockHttpServletRequest secReq = new MockHttpServletRequest("GET", "/api/v1/auth/me");
        secReq.addHeader("X-Forwarded-Proto", "https");
        MockHttpServletResponse secRes = new MockHttpServletResponse();
        GatewaySecurityHeadersFilter.applySecurityHeaders(secReq, secRes);
        assertEquals("nosniff", secRes.getHeader("X-Content-Type-Options"));
        assertEquals("DENY", secRes.getHeader("X-Frame-Options"));
        assertEquals("no-referrer", secRes.getHeader("Referrer-Policy"));
        assertEquals("max-age=31536000; includeSubDomains", secRes.getHeader("Strict-Transport-Security"));
    }
}

