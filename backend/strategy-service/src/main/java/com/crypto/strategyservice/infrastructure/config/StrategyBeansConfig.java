package com.crypto.strategyservice.infrastructure.config;

import com.crypto.strategyservice.application.port.ModelClient;
import com.crypto.strategyservice.application.port.TrainingProvider;
import com.crypto.strategyservice.infrastructure.client.HttpTrainingProvider;
import com.crypto.strategyservice.infrastructure.client.fixture.FakeModelClient;
import com.crypto.strategyservice.infrastructure.client.fixture.FakeTrainingProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.net.URI;
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

    @Bean
    @Profile("training-http & !fixture")
    public TrainingProvider httpTrainingProvider(
            @Value("${training.base-url}") String baseUrl,
            @Value("${training.service-secret}") String serviceSecret,
            Clock clock
    ) {
        return new HttpTrainingProvider(URI.create(baseUrl), serviceSecret, clock);
    }

    @Bean
    @ConditionalOnMissingBean(TrainingProvider.class)
    public FakeTrainingProvider fallbackFixtureTrainingProvider() {
        return new FakeTrainingProvider();
    }
}
