package com.paicoding.paiswitch.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicoding.paiswitch.domain.entity.ModelProvider;
import com.paicoding.paiswitch.domain.enums.TargetTool;
import com.paicoding.paiswitch.repository.ModelProviderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class LocalConfigService {

    private static final String CONFIG_PATH = System.getProperty("user.home") + "/.claude/settings.json";
    private static final String CLAUDE_OFFICIAL_BASE_URL = "https://api.anthropic.com";
    private static final String CLAUDE_DEFAULT_MODEL = "claude-sonnet-4-20250514";
    private static final String CLAUDE_DEFAULT_SMALL_MODEL = "claude-3-5-haiku-latest";
    private static final String SETTINGS_MODEL_KEY = "model";

    private final ModelProviderRepository providerRepository;
    private static final Map<String, String> BASE_URL_TO_PROVIDER = Map.of(
            "api.anthropic.com", "claude",
            "api.deepseek.com", "deepseek",
            "open.bigmodel.cn", "zhipu",
            "openrouter.ai", "openrouter"
    );

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Sync settings.json configuration to database.
     * Updates the provider's baseUrl, modelName, and modelNameSmall in the database.
     */
    @Transactional
    public void syncLocalConfigToDatabase() {
        LocalConfig localConfig = readLocalConfig();
        String providerCode = localConfig.providerCode();

        providerRepository.findByCodeAndTargetTool(providerCode, TargetTool.CLAUDE_CODE).ifPresent(provider -> {
            boolean updated = false;
            String normalizedBaseUrl = localConfig.baseUrl();

            if ("claude".equals(providerCode)) {
                if (!CLAUDE_OFFICIAL_BASE_URL.equals(provider.getBaseUrl())) {
                    provider.setBaseUrl(CLAUDE_OFFICIAL_BASE_URL);
                    updated = true;
                }
                if (!CLAUDE_DEFAULT_MODEL.equals(provider.getModelName())) {
                    provider.setModelName(CLAUDE_DEFAULT_MODEL);
                    updated = true;
                }
                if (!CLAUDE_DEFAULT_SMALL_MODEL.equals(provider.getModelNameSmall())) {
                    provider.setModelNameSmall(CLAUDE_DEFAULT_SMALL_MODEL);
                    updated = true;
                }
            } else {
                if (normalizedBaseUrl != null && !normalizedBaseUrl.isEmpty()) {
                    if (!normalizedBaseUrl.equals(provider.getBaseUrl())) {
                        provider.setBaseUrl(normalizedBaseUrl);
                        updated = true;
                    }
                }

                if (localConfig.model() != null && !localConfig.model().isEmpty()) {
                    if (!localConfig.model().equals(provider.getModelName())) {
                        provider.setModelName(localConfig.model());
                        updated = true;
                    }
                }

                if (localConfig.smallModel() != null && !localConfig.smallModel().isEmpty()) {
                    if (!localConfig.smallModel().equals(provider.getModelNameSmall())) {
                        provider.setModelNameSmall(localConfig.smallModel());
                        updated = true;
                    }
                }
            }

            if (updated) {
                providerRepository.save(provider);
                log.info("Synced local config to database for provider: {}, model: {}", providerCode, localConfig.model());
            } else {
                log.info("Local config already in sync with database for provider: {}", providerCode);
            }
        });
    }

    public LocalConfig readLocalConfig() {
        try {
            Path path = Paths.get(CONFIG_PATH);
            if (!Files.exists(path)) {
                log.warn("Local config file not found: {}", CONFIG_PATH);
                return new LocalConfig("claude", null, null, 600000);
            }

            String content = Files.readString(path);
            JsonNode root = objectMapper.readTree(content);
            JsonNode env = root.get("env");
            String topLevelModel = getText(root, SETTINGS_MODEL_KEY, "");

            if (env == null) {
                return new LocalConfig("claude", topLevelModel.isEmpty() ? null : topLevelModel, null, 600000);
            }

            String baseUrl = getText(env, "ANTHROPIC_BASE_URL", "");
            String apiKey = getText(env, "ANTHROPIC_API_KEY", "");
            String authToken = getText(env, "ANTHROPIC_AUTH_TOKEN", "");
            String envModel = getText(env, "ANTHROPIC_MODEL", "");
            String smallModel = getText(env, "ANTHROPIC_SMALL_FAST_MODEL", "");
            int timeout = getInt(env, "API_TIMEOUT_MS", 600000);

            String providerCode = detectProvider(baseUrl);
            String effectiveApiKey = apiKey.isEmpty() ? authToken : apiKey;
            String model = "claude".equals(providerCode) && !topLevelModel.isEmpty() ? topLevelModel : envModel;

            log.info("Read local config: provider={}, model={}", providerCode, model);

            return new LocalConfig(providerCode, model.isEmpty() ? null : model,
                    smallModel.isEmpty() ? null : smallModel, timeout, effectiveApiKey, baseUrl);

        } catch (Exception e) {
            log.error("Failed to read local config: {}", e.getMessage());
            return new LocalConfig("claude", null, null, 600000);
        }
    }

    private String detectProvider(String baseUrl) {
        if (baseUrl == null || baseUrl.isEmpty()) {
            return "claude";
        }

        String lowerUrl = baseUrl.toLowerCase();
        for (Map.Entry<String, String> entry : BASE_URL_TO_PROVIDER.entrySet()) {
            if (lowerUrl.contains(entry.getKey())) {
                return entry.getValue();
            }
        }

        return "claude";
    }

    private String getText(JsonNode node, String key, String defaultValue) {
        JsonNode value = node.get(key);
        return value != null ? value.asText(defaultValue) : defaultValue;
    }

    private int getInt(JsonNode node, String key, int defaultValue) {
        JsonNode value = node.get(key);
        if (value != null && value.isNumber()) {
            return value.asInt();
        }
        if (value != null) {
            try {
                return Integer.parseInt(value.asText());
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    public record LocalConfig(
            String providerCode,
            String model,
            String smallModel,
            int apiTimeout,
            String apiKey,
            String baseUrl
    ) {
        public LocalConfig(String providerCode, String model, String smallModel, int apiTimeout) {
            this(providerCode, model, smallModel, apiTimeout, null, null);
        }
    }
}
