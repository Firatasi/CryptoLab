package com.crypto.strategyservice.infrastructure.config;

import com.crypto.strategyservice.application.port.ModelClient;
import com.crypto.strategyservice.infrastructure.client.fixture.FakeModelClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class StrategyBeansConfig {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean(ModelClient.class)
    public ModelClient fallbackFixtureModelClient() {
        return new FakeModelClient();
    }
}

