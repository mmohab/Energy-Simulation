package com.energysim;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Swagger UI: {@code /swagger-ui.html} · raw OpenAPI spec: {@code /v3/api-docs}
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@OpenAPIDefinition(info = @Info(
        title = "Neighbourhood Energy Simulation API",
        version = "1.0.0",
        description = "Drives the neighbourhood electricity-use/generation simulation: advance time, "
                + "inspect the current state and history, and configure or regenerate the neighbourhood "
                + "(house count, asset proportions, public chargers, random seed, tick size, etc).",
        contact = @Contact(name = "Neighbourhood Energy Simulation")
))
public class EnergySimApplication {

    public static void main(String[] args) {
        SpringApplication.run(EnergySimApplication.class, args);
    }

}
