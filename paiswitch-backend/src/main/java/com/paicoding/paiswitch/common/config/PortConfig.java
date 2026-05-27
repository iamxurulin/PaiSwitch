package com.paicoding.paiswitch.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.servlet.context.ServletWebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class PortConfig {

    @Bean
    public ApplicationListener<ServletWebServerInitializedEvent> portLogger() {
        return event -> {
            int port = event.getWebServer().getPort();
            log.info("========================================");
            log.info("PaiSwitch Backend started successfully");
            log.info("Swagger UI: http://localhost:{}/swagger-ui.html", port);
            log.info("API Docs:   http://localhost:{}/api-docs", port);
            log.info("========================================");
        };
    }
}
