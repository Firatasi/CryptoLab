package com.crypto.simulationservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SimulationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SimulationServiceApplication.class, args);
    }

}
