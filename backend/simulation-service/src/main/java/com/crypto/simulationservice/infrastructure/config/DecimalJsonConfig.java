package com.crypto.simulationservice.infrastructure.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.crypto.financecore.finance.Decimal;
import org.springframework.context.annotation.*;
import java.math.BigDecimal;
import java.io.IOException;

@Configuration
public class DecimalJsonConfig {

    @Bean
    public com.fasterxml.jackson.databind.Module decimals() {
        var module = new SimpleModule();
        module.addSerializer(BigDecimal.class, new JsonSerializer<BigDecimal>() {
            @Override
            public void serialize(BigDecimal value, JsonGenerator generator, SerializerProvider provider) throws IOException {
                generator.writeString(Decimal.wire(value)); // JSON number değil quoted canonical Decimal.
            }
        });
        return module;
    }
}
