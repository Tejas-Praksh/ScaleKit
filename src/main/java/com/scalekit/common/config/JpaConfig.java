package com.scalekit.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * JPA configuration — separating {@link EnableJpaAuditing} from the main
 * {@code @SpringBootApplication} class so that {@code @WebMvcTest} slices
 * do not fail with "JPA metamodel must not be empty".
 */
@Configuration
@EnableJpaAuditing
public class JpaConfig {
}
