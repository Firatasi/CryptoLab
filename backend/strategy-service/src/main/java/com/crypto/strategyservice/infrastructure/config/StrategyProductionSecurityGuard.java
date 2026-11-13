package com.crypto.strategyservice.infrastructure.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Canlı ortamda model/training iç servis paylaşımlı sırrının varsayılan dev değeriyle kalmasını engeller.
 */
@Component
@Profile("prod")
public class StrategyProductionSecurityGuard implements InitializingBean {

    private final String modelServiceSecret;

    public StrategyProductionSecurityGuard(@Value("${model.service-secret}") String modelServiceSecret) {
        this.modelServiceSecret = modelServiceSecret;
    }

    @Override
    public void afterPropertiesSet() {
        if (modelServiceSecret == null
                || modelServiceSecret.length() < 32
                || "cryptolab-secret-model-service-32bytes-min".equals(modelServiceSecret)) {
            throw new IllegalStateException("PROD_SECURITY_CONFIG: Weak or default MODEL_SERVICE_SECRET in prod");
        }
    }
}
