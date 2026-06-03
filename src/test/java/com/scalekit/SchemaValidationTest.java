package com.scalekit;

import com.scalekit.config.TestContainersConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import javax.sql.DataSource;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test verifying database schema, indexes, and connections using Testcontainers.
 */
@SpringBootTest
@ActiveProfiles("test")
@ContextConfiguration(initializers = TestContainersConfig.Initializer.class)
@Import(TestContainersConfig.class)
public class SchemaValidationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired(required = false)
    private RedisConnectionFactory redisConnectionFactory;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(TestContainersConfig.isDockerAvailable(),
                "Docker is not available. Skipping integration test.");
    }

    @Test
    void allTables_exist_afterSchemaCreation() {
        List<String> tables = jdbcTemplate.query(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                (rs, rowNum) -> rs.getString("table_name")
        );

        assertTrue(tables.contains("urls"), "urls table should exist");
        assertTrue(tables.contains("url_analytics"), "url_analytics table should exist");
        assertTrue(tables.contains("url_daily_stats"), "url_daily_stats table should exist");
        assertTrue(tables.contains("rate_limit_rules"), "rate_limit_rules table should exist");
        assertTrue(tables.contains("rate_limit_audit_logs"), "rate_limit_audit_logs table should exist");
        assertTrue(tables.contains("system_metrics"), "system_metrics table should exist");
        assertTrue(tables.contains("algorithm_benchmarks"), "algorithm_benchmarks table should exist");
    }

    @Test
    void urlTable_hasCorrectIndexes() {
        List<String> indexes = jdbcTemplate.query(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'public' AND tablename = 'urls'",
                (rs, rowNum) -> rs.getString("indexname")
        );

        assertTrue(indexes.contains("idx_urls_short_code"), "short_code index should exist");
        assertTrue(indexes.contains("idx_urls_custom_alias"), "custom_alias index should exist");
        assertTrue(indexes.contains("idx_urls_created_at"), "created_at index should exist");
    }

    @Test
    void analyticsTable_hasCorrectColumns() {
        List<String> columns = jdbcTemplate.query(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'url_analytics'",
                (rs, rowNum) -> rs.getString("column_name")
        );

        assertTrue(columns.contains("short_code"), "short_code column should exist");
        assertTrue(columns.contains("clicked_at"), "clicked_at column should exist");
        assertTrue(columns.contains("ip_address"), "ip_address column should exist");
        assertTrue(columns.contains("country"), "country column should exist");
        assertTrue(columns.contains("city"), "city column should exist");
    }

    @Test
    void redisConnection_isHealthy() {
        assertNotNull(redisConnectionFactory, "RedisConnectionFactory should be autowired");
        String response = Objects.requireNonNull(redisConnectionFactory.getConnection().ping());
        assertEquals("PONG", response, "Redis ping response should be PONG");
    }

    @Test
    void hikariPool_initializes_correctly() {
        assertTrue(dataSource instanceof HikariDataSource, "DataSource should be a HikariDataSource");
        HikariDataSource hikari = (HikariDataSource) dataSource;
        assertEquals(20, hikari.getMaximumPoolSize(), "Maximum pool size should be 20");
        assertEquals(5, hikari.getMinimumIdle(), "Minimum idle connections should be 5");
    }
}
