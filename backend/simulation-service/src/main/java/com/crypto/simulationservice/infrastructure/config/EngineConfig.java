package com.crypto.simulationservice.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;

import java.time.Clock;

@Configuration
public class EngineConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
    @Bean
    public BeanPostProcessor kafkaContainerStoppingErrorHandlerConfigurer() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof ConcurrentKafkaListenerContainerFactory<?, ?> factory) {
                    factory.setCommonErrorHandler(new CommonContainerStoppingErrorHandler());
                }
                return bean;
            }
        };
    }
}