package com.paicoding.paiswitch.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicoding.paiswitch.domain.entity.ApiKey;
import com.paicoding.paiswitch.domain.entity.ModelProvider;
import com.paicoding.paiswitch.repository.ApiKeyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Optional;

/**
 * Service for writing configuration to Claude Code settings.json file.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettingsWriterService {

    private static final String SETTINGS_PATH_PROPERTY = "paiswitch.settings.path";
    private static final String SETTINGS_MODEL_KEY = "model";

    private static final String OPENAI_WIRE_API = "openai";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ApiKeyRepository apiKeyRepository;
    private final EncryptionService encryptionService;
    private final com.paicoding.paiswitch.proxy.ProxyEndpointResolver proxyEndpointResolver;

    /**
     * Write provider configuration to settings.json.
     *
     * @param userId the user ID
     * @param provider the provider to switch to
     */
    public void writeToSettings(Long userId, ModelProvider provider) {
        try {
            Path path = resolveConfigPath();

            // Read existing config or create new
            ObjectNode root;
            if (Files.exists(path)) {
                String content = Files.readString(path);
                JsonNode parsed = objectMapper.readTree(content);
                root = (ObjectNode) parsed;
            } else {
                root = objectMapper.createObjectNode();
            }

            // Get or create env object
            ObjectNode env = (ObjectNode) root.get("env");
            if (env == null) {
                env = objectMapper.createObjectNode();
                root.set("env", env);
            }

            // Clear previous provider-specific config before writing the next target provider.
            clearProviderConfig(root, env);

            // Set new provider configuration
            String providerCode = provider.getCode();

            if ("claude".equals(providerCode)) {
                // Claude official mode should not keep a pinned top-level model when switching back.
                log.info("Writing Claude official config without pinned top-level model");
            } else {
                // Third-party provider. If wire_api='openai', the upstream speaks
                // OpenAI chat-completions, not Anthropic Messages — point Claude
                // Code at the local /claude-proxy/{code} which translates both
                // ways (in particular maps usage.prompt_tokens → input_tokens so
                // Claude Code doesn't crash on `_.input_tokens`).
                String baseUrl = needsProxy(provider)
                        ? proxyEndpointResolver.getProxyBaseUrl() + "/claude-proxy/" + providerCode
                        : normalizeClaudeCodeBaseUrl(provider.getBaseUrl());
                env.put("ANTHROPIC_BASE_URL", baseUrl);

                // Set model names
                if (provider.getModelName() != null && !provider.getModelName().isBlank()) {
                    env.put("ANTHROPIC_MODEL", provider.getModelName());
                }
                if (provider.getModelNameSmall() != null && !provider.getModelNameSmall().isBlank()) {
                    env.put("ANTHROPIC_SMALL_FAST_MODEL", provider.getModelNameSmall());
                }

                // Get API key for this provider
                Optional<ApiKey> apiKeyOpt = apiKeyRepository.findByUserIdAndProviderId(userId, provider.getId());
                if (apiKeyOpt.isPresent()) {
                    String decryptedKey = encryptionService.decrypt(apiKeyOpt.get().getEncryptedKey());
                    env.put("ANTHROPIC_AUTH_TOKEN", decryptedKey);
                }
            }

            // Set API timeout
            env.put("API_TIMEOUT_MS", 600000);

            // Create backup before writing
            createBackup(path);

            // Write new config
            String jsonContent = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
            Files.writeString(path, jsonContent);

            log.info("Successfully wrote settings.json for provider: {}", providerCode);

        } catch (IOException e) {
            log.error("Failed to write settings.json: {}", e.getMessage());
            throw new RuntimeException("Failed to write settings.json", e);
        }
    }

    private Path resolveConfigPath() {
        String overridePath = System.getProperty(SETTINGS_PATH_PROPERTY);
        if (overridePath != null && !overridePath.isBlank()) {
            return Paths.get(overridePath);
        }

        return Paths.get(System.getProperty("user.home"), ".claude", "settings.json");
    }

    private void clearProviderConfig(ObjectNode root, ObjectNode env) {
        root.remove(SETTINGS_MODEL_KEY);
        env.remove("ANTHROPIC_BASE_URL");
        env.remove("ANTHROPIC_API_KEY");
        env.remove("ANTHROPIC_AUTH_TOKEN");
        env.remove("ANTHROPIC_MODEL");
        env.remove("ANTHROPIC_SMALL_FAST_MODEL");
    }

    private boolean needsProxy(ModelProvider provider) {
        String wireApi = provider.getWireApi();
        return wireApi != null && OPENAI_WIRE_API.equalsIgnoreCase(wireApi.trim());
    }

    private String normalizeClaudeCodeBaseUrl(String baseUrl) {
        if (baseUrl == null) {
            return null;
        }

        String normalized = baseUrl.trim().replaceAll("/+$", "");
        return normalized.replaceAll("/v1$", "");
    }

    private void createBackup(Path path) throws IOException {
        if (Files.exists(path)) {
            String timestamp = Instant.now().toString().replace(":", "-");
            Path backupPath = Paths.get(path.toString() + ".backup." + timestamp);
            Files.copy(path, backupPath);
            log.info("Created backup: {}", backupPath);
        }
    }
}
