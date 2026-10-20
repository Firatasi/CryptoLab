package com.crypto.apigateway;

import com.crypto.apigateway.infrastructure.config.RouteTable;
import org.junit.jupiter.api.Test;

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
        assertThrows(IllegalArgumentException.class, () -> RouteTable.serviceId("/api/v1/unknown/path"));
    }
}

