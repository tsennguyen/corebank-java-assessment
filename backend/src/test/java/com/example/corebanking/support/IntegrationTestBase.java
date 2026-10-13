package com.example.corebanking.support;

import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for integration tests running against a real PostgreSQL container. When Docker is
 * absent (e.g. local developer laptop), tests extending this class are gracefully disabled without
 * failing the build (ADR-018). Uses the singleton container pattern to keep the database running
 * across all integration test classes in the test suite.
 */
@ExtendWith(DisabledIfNoDockerCondition.class)
@ActiveProfiles("test")
@SpringBootTest
public abstract class IntegrationTestBase {

    public static final PostgreSQLContainer<?> postgres;

    static {
        if (isDockerAvailable()) {
            postgres = new PostgreSQLContainer<>("postgres:17-alpine");
            postgres.start();
        } else {
            postgres = null;
        }
    }

    private static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @DynamicPropertySource
    static void configureDatabaseProperties(DynamicPropertyRegistry registry) {
        if (postgres != null && postgres.isRunning()) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", postgres::getUsername);
            registry.add("spring.datasource.password", postgres::getPassword);
            registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
        }
    }
}
