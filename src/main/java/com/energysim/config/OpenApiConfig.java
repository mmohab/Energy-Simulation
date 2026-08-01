package com.energysim.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger UI / OpenAPI metadata. springdoc-openapi auto-generates the API
 * spec from the controllers and DTOs; this just adds the human-readable
 * title/description shown at the top of the UI.
 *
 * <p>Once the app is running:
 * <ul>
 *   <li>Swagger UI: {@code http://localhost:8080/swagger-ui.html}</li>
 *   <li>Raw OpenAPI spec (JSON): {@code http://localhost:8080/v3/api-docs}</li>
 * </ul>
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI neighbourhoodEnergySimOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Neighbourhood Energy Simulation API")
                        .description("Drives the neighbourhood electricity-use/generation simulation: "
                                + "advance time, inspect the current state and history, and configure "
                                + "or regenerate the neighbourhood (house count, asset proportions, "
                                + "public chargers, random seed, tick size, etc).")
                        .version("1.0.0")
                        .contact(new Contact().name("Neighbourhood Energy Simulation")));
    }
}
