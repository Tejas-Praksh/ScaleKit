package com.scalekit.common.config;

import com.scalekit.config.TestContainersConfig;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@ContextConfiguration(initializers = TestContainersConfig.Initializer.class)
@Import(TestContainersConfig.class)
class RedisConfigTest {

    @Autowired(required = false)
    private RedisConnectionFactory connectionFactory;

    @Autowired(required = false)
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired(required = false)
    private StringRedisTemplate stringRedisTemplate;

    @Autowired(required = false)
    private RedisTemplate<String, Long> longRedisTemplate;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(TestContainersConfig.isDockerAvailable(),
                "Docker is not available. Skipping RedisConfigTest.");
    }

    @Test
    void contextLoads() {
        assertThat(connectionFactory).isNotNull();
    }

    @Test
    void beansConfiguredCorrectly() {
        assertThat(redisTemplate).isNotNull();
        assertThat(stringRedisTemplate).isNotNull();
        assertThat(longRedisTemplate).isNotNull();

        assertThat(redisTemplate.getConnectionFactory()).isSameAs(connectionFactory);
        assertThat(stringRedisTemplate.getConnectionFactory()).isSameAs(connectionFactory);
        assertThat(longRedisTemplate.getConnectionFactory()).isSameAs(connectionFactory);

        // Verify serializers
        assertThat(redisTemplate.getKeySerializer()).isInstanceOf(org.springframework.data.redis.serializer.StringRedisSerializer.class);
        assertThat(redisTemplate.getValueSerializer()).isInstanceOf(org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer.class);
        assertThat(longRedisTemplate.getValueSerializer()).isInstanceOf(org.springframework.data.redis.serializer.GenericToStringSerializer.class);
    }
}
