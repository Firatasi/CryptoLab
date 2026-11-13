package com.crypto.reportingservice.infrastructure.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Canlı ortamda rapor cursor HMAC anahtarının varsayılan dev değeriyle kalmasını engeller.
 */
@Component
@Profile("prod")
public class ReportingProductionSecurityGuard implements InitializingBean {

    private final String cursorSecret;

    public ReportingProductionSecurityGuard(@Value("${reporting.cursor-secret}") String cursorSecret) {
        this.cursorSecret = cursorSecret;
    }

    @Override
    public void afterPropertiesSet() {
        if (cursorSecret == null
                || cursorSecret.length() < 32
                || "cryptolab-reporting-cursor-hmac-secret-2026".equals(cursorSecret)) {
            throw new IllegalStateException("PROD_SECURITY_CONFIG: Weak or default REPORTING_CURSOR_SECRET in prod");
        }
    }
}
