package com.crypto.identityservice.infrastructure.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Canlı (prod) profilde varsayılan veya zayıf güvenlik yapılandırmalarının başlatılmasını engeller.
 */
@Component
@Profile("prod")
public class ProductionSecurityGuard implements InitializingBean {

    private final boolean secureCookie;
    private final String adminPassword;
    private final String registrationHashKey;
    private final String webOrigin;

    public ProductionSecurityGuard(
            @Value("${cookie.secure:true}") boolean secureCookie,
            @Value("${admin.bootstrap.password:cryptoadmin15}") String adminPassword,
            @Value("${registration.hash-key}") String registrationHashKey,
            @Value("${web.origin}") String webOrigin
    ) {
        this.secureCookie = secureCookie;
        this.adminPassword = adminPassword;
        this.registrationHashKey = registrationHashKey;
        this.webOrigin = webOrigin;
    }

    @Override
    public void afterPropertiesSet() {
        if (!secureCookie) {
            throw new IllegalStateException("PROD_SECURITY_CONFIG: COOKIE_SECURE must be true in prod");
        }
        if (adminPassword == null || adminPassword.length() < 14 || "cryptoadmin15".equals(adminPassword)) {
            throw new IllegalStateException("PROD_SECURITY_CONFIG: Weak or default ADMIN_BOOTSTRAP_PASSWORD in prod");
        }
        if (registrationHashKey == null || registrationHashKey.length() < 32
                || registrationHashKey.toLowerCase(Locale.ROOT).contains("dev")) {
            throw new IllegalStateException("PROD_SECURITY_CONFIG: Weak REGISTRATION_HASH_KEY in prod");
        }
        if (webOrigin == null || !webOrigin.startsWith("https://")) {
            throw new IllegalStateException("PROD_SECURITY_CONFIG: WEB_ORIGIN must use https:// in prod");
        }
    }
}
