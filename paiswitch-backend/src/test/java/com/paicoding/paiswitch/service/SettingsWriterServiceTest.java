package com.paicoding.paiswitch.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicoding.paiswitch.domain.entity.ModelProvider;
import com.paicoding.paiswitch.proxy.ProxyEndpointResolver;
import com.paicoding.paiswitch.repository.ApiKeyRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

class SettingsWriterServiceTest {

    private static final String SETTINGS_PATH_PROPERTY = "paiswitch.settings.path";

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void tearDown() {
        System.clearProperty(SETTINGS_PATH_PROPERTY);
    }

    @Test
    void shouldClearPinnedModelWhenSwitchingBackToClaude() throws IOException {
        Path claudeDir = tempDir.resolve(".claude");
        Files.createDirectories(claudeDir);
        Path settingsPath = claudeDir.resolve("settings.json");
        Files.writeString(settingsPath, """
                {
                  "model": "anthropic/claude-sonnet-4",
                  "env": {
                    "ANTHROPIC_BASE_URL": "https://openrouter.ai/api/v1",
                    "ANTHROPIC_MODEL": "anthropic/claude-sonnet-4",
                    "ANTHROPIC_SMALL_FAST_MODEL": "anthropic/claude-3-haiku",
                    "ANTHROPIC_AUTH_TOKEN": "secret",
                    "API_TIMEOUT_MS": 120000
                  }
                }
                """);
        System.setProperty(SETTINGS_PATH_PROPERTY, settingsPath.toString());

        SettingsWriterService service = new SettingsWriterService(
                mock(ApiKeyRepository.class),
                mock(EncryptionService.class),
                mock(ProxyEndpointResolver.class));
        ModelProvider provider = ModelProvider.builder()
                .id(1L)
                .code("claude")
                .name("Claude (Official)")
                .baseUrl("https://api.anthropic.com")
                .modelName("claude-sonnet-4-20250514")
                .build();

        service.writeToSettings(1L, provider);

        JsonNode root = objectMapper.readTree(Files.readString(settingsPath));
        JsonNode env = root.get("env");

        assertFalse(root.has("model"));
        assertFalse(env.has("ANTHROPIC_BASE_URL"));
        assertFalse(env.has("ANTHROPIC_MODEL"));
        assertFalse(env.has("ANTHROPIC_SMALL_FAST_MODEL"));
        assertFalse(env.has("ANTHROPIC_AUTH_TOKEN"));
        assertEquals(600000, env.get("API_TIMEOUT_MS").asInt());
    }
}
