package com.scalekit.common.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;

/**
 * Production database configuration.
 * Automatically converts Render postgresql:// or postgres:// connection strings
 * to JDBC-compliant jdbc:postgresql:// format.
 */
@Configuration
@Profile("prod")
@Slf4j
public class DatabaseConfig {

    @Value("${POSTGRES_URL}")
    private String postgresUrl;

    @Value("${POSTGRES_USER}")
    private String username;

    @Value("${POSTGRES_PASSWORD}")
    private String password;

    @Bean
    public DataSource dataSource() {
        log.info("Configuring production PostgreSQL database connection...");
        String jdbcUrl = postgresUrl;
        if (jdbcUrl.startsWith("postgres://")) {
            jdbcUrl = jdbcUrl.replace("postgres://", "jdbc:postgresql://");
        } else if (jdbcUrl.startsWith("postgresql://")) {
            jdbcUrl = jdbcUrl.replace("postgresql://", "jdbc:postgresql://");
        }

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setDriverClassName("org.postgresql.Driver");

        // Performance optimizations for production
        config.setMaximumPoolSize(20);
        config.setMinimumIdle(5);
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);

        return new HikariDataSource(config);
    }
}
