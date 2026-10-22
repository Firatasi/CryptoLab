package com.crypto.strategyservice.infrastructure.config;

import com.crypto.strategyservice.application.port.ModelClient;
import com.crypto.strategyservice.infrastructure.client.HttpModelClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Clock;

@Configuration
@Profile("model-http & !fixture")
public class ModelClientConfig {

    @Bean
    public ModelClient httpModelClient(
            @Value("${model.base-url}") String baseUrl,
            @Value("${model.service-secret}") String serviceSecret
    ) {
        // ActiveModelBundle cevabındaki ek alanlara toleranslıdır.
        // Command DTO'larının global mapper ayarını değiştirmez.
        var modelJson = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();

        return new HttpModelClient(
                URI.create(baseUrl),
                serviceSecret,
                modelJson,
                Clock.systemUTC()
        );
    }
}