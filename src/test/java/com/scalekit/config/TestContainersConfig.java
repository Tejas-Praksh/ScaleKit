package com.scalekit.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.test.util.TestPropertyValues;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared Testcontainers configuration for integration testing.
 * Fallbacks gracefully to H2 and skips container execution if Docker is unavailable.
 */
@TestConfiguration
public class TestContainersConfig {

    public static PostgreSQLContainer<?> postgres;
    public static GenericContainer<?> redis;
    private static boolean dockerAvailable = false;

    static {
        try {
            dockerAvailable = DockerClientFactory.instance().isDockerAvailable();
            if (dockerAvailable) {
                postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:15-alpine"))
                        .withDatabaseName("scalekit")
                        .withUsername("scalekit")
                        .withPassword("scalekit123");

                redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                        .withExposedPorts(6379)
                        .withCommand("redis-server --requirepass redis123");

                postgres.start();
                redis.start();
            }
        } catch (Throwable e) {
            dockerAvailable = false;
            System.err.println("Testcontainers initialization failed (Docker is likely not running): " + e.getMessage());
        }
    }

    /**
     * Checks if Docker is running and Testcontainers was successfully initialized.
     */
    public static boolean isDockerAvailable() {
        return dockerAvailable;
    }

    /**
     * Dynamic environment properties initializer.
     */
    public static class Initializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            if (dockerAvailable) {
                TestPropertyValues.of(
                        "spring.datasource.url=" + postgres.getJdbcUrl(),
                        "spring.datasource.username=" + postgres.getUsername(),
                        "spring.datasource.password=" + postgres.getPassword(),
                        "spring.datasource.driver-class-name=org.postgresql.Driver",
                        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
                        "spring.jpa.hibernate.ddl-auto=none",
                        "spring.sql.init.mode=always",
                        "spring.sql.init.schema-locations=classpath:db/schema.sql",
                        "spring.data.redis.host=" + redis.getHost(),
                        "spring.data.redis.port=" + redis.getMappedPort(6379),
                        "spring.data.redis.password=redis123"
                ).applyTo(context.getEnvironment());
            } else {
                System.out.println("Docker not detected. Proceeding with in-memory H2 database fallback.");
            }
        }
    }
}
