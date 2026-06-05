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
 * Automatically parses Render postgresql:// or postgres:// connection strings
 * (which include embedded credentials) and builds a clean JDBC connection URL.
 */
@Configuration
@Profile("prod")
@Slf4j
public class DatabaseConfig {

    @Value("${POSTGRES_URL}")
    private String postgresUrl;

    @Value("${POSTGRES_USER:}")
    private String fallbackUsername;

    @Value("${POSTGRES_PASSWORD:}")
    private String fallbackPassword;

    @Bean
    public DataSource dataSource() {
        log.info("Parsing and configuring production PostgreSQL database connection...");
        
        String uriStr = postgresUrl;
        if (uriStr.startsWith("postgres://")) {
            uriStr = uriStr.substring("postgres://".length());
        } else if (uriStr.startsWith("postgresql://")) {
            uriStr = uriStr.substring("postgresql://".length());
        }

        // Now we have username:password@host:port/database
        int atIndex = uriStr.lastIndexOf('@');
        String dbUser = fallbackUsername;
        String dbPassword = fallbackPassword;
        String hostPortDb = uriStr;

        if (atIndex >= 0) {
            String credentials = uriStr.substring(0, atIndex);
            hostPortDb = uriStr.substring(atIndex + 1);

            int colonIndex = credentials.indexOf(':');
            if (colonIndex >= 0) {
                dbUser = credentials.substring(0, colonIndex);
                dbPassword = credentials.substring(colonIndex + 1);
            } else {
                dbUser = credentials;
            }
        }

        String jdbcUrl = "jdbc:postgresql://" + hostPortDb;
        log.info("Configured JDBC URL: jdbc:postgresql://{}/[database]", hostPortDb.substring(hostPortDb.indexOf('/') + 1));

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(dbUser);
        config.setPassword(dbPassword);
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
