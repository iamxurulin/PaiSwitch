package com.paicoding.paiswitch.common.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultBootstrapContext;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.mock.env.MockEnvironment;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class DatabaseInitializerTest {

    private final DatabaseInitializer initializer = new DatabaseInitializer();

    @AfterEach
    void tearDown() throws Exception {
        Field initializedField = DatabaseInitializer.class.getDeclaredField("initialized");
        initializedField.setAccessible(true);
        initializedField.set(null, false);
    }

    @Test
    void shouldIgnoreNonMySqlDatasource() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=MySQL")
                .withProperty("spring.datasource.username", "sa")
                .withProperty("spring.datasource.password", "");

        ApplicationEnvironmentPreparedEvent event = new ApplicationEnvironmentPreparedEvent(
                new DefaultBootstrapContext(),
                new SpringApplication(),
                new String[0],
                environment
        );

        assertDoesNotThrow(() -> initializer.onApplicationEvent(event));
    }
}
