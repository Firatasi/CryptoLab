package com.crypto.simulationservice.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class EngineConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}