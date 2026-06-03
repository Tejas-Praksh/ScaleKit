package com.scalekit.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI / Swagger configuration for ScaleKit.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI scaleKitOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("ScaleKit API")
                        .description("Distributed Systems Toolkit — URL Shortener, Rate Limiter, Distributed Cache")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("ScaleKit")
                                .url("https://github.com/scalekit"))
                        .license(new License()
                                .name("MIT")
                                .url("https://opensource.org/licenses/MIT")))
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("Local Development")
                ));
    }
}
