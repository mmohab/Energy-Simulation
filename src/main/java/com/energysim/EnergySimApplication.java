package com.energysim;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class EnergySimApplication {

    public static void main(String[] args) {
        SpringApplication.run(EnergySimApplication.class, args);
    }

}
