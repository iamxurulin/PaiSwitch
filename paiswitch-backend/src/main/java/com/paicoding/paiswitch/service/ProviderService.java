package com.paicoding.paiswitch.service;

import com.paicoding.paiswitch.common.exception.BusinessException;
import com.paicoding.paiswitch.common.response.ResponseCode;
import com.paicoding.paiswitch.domain.dto.ProviderDto;
import com.paicoding.paiswitch.domain.entity.ApiKey;
import com.paicoding.paiswitch.domain.entity.ModelProvider;
import com.paicoding.paiswitch.repository.ApiKeyRepository;
import com.paicoding.paiswitch.repository.ModelProviderRepository;
import com.paicoding.paiswitch.repository.UserConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProviderService {

    private static final String CLAUDE_PROVIDER_CODE = "claude";
    private static final String CLAUDE_OFFICIAL_BASE_URL = "https://api.anthropic.com";
    private static final String CLAUDE_DEFAULT_MODEL = "claude-sonnet-4-20250514";
    private static final String CLAUDE_DEFAULT_SMALL_MODEL = "claude-3-5-haiku-latest";

    private enum ProviderApiStyle {
        OPENROUTER,
        ANTHROPIC,
        OPENAI
    }

    private final ModelProviderRepository providerRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final UserConfigRepository configRepository;
    private final EncryptionService encryptionService;
    private final SettingsWriterService settingsWriterService;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Transactional(readOnly = true)
    public List<ProviderDto.ProviderInfo> getAllProviders() {
        return providerRepository.findByIsActiveTrueOrderBySortOrderAsc().stream()
                .map(this::mapToProviderInfo)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<ProviderDto.ProviderInfo> getProvidersForUser(Long userId) {
        List<ModelProvider> providers = providerRepository.findByIsActiveTrueOrderBySortOrderAsc();
        return providers.stream()
                .map(provider -> {
                    ProviderDto.ProviderInfo info = mapToProviderInfo(provider);
                    boolean hasApiKey = apiKeyRepository.existsByUserIdAndProviderId(userId, provider.getId());
                    return ProviderDto.ProviderInfo.builder()
                            .id(info.getId())
                            .code(info.getCode())
                            .name(info.getName())
                            .description(info.getDescription())
                            .baseUrl(info.getBaseUrl())
                            .modelName(info.getModelName())
                            .modelNameSmall(info.getModelNameSmall())
                            .isBuiltin(info.getIsBuiltin())
                            .isActive(info.getIsActive())
                            .sortOrder(info.getSortOrder())
                            .iconUrl(info.getIconUrl())
                            .hasApiKey(hasApiKey)
                            .createdAt(info.getCreatedAt())
                            .build();
                })
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public ProviderDto.ProviderInfo getProviderByCode(String code) {
        ModelProvider provider = providerRepository.findByCode(code)
                .orElseThrow(() -> new BusinessException(ResponseCode.PROVIDER_NOT_FOUND));
        return mapToProviderInfo(provider);
    }

    @Transactional
    public ProviderDto.ProviderInfo createCustomProvider(Long userId, ProviderDto.CreateRequest request) {
        String code = sanitizeCode(request.getCode());
        if (providerRepository.existsByCode(code)) {
            throw new BusinessException(ResponseCode.PROVIDER_ALREADY_EXISTS);
        }

        ModelProvider provider = ModelProvider.builder()
                .code(code)
                .name(sanitizeOptionalText(request.getName()))
                .description(sanitizeOptionalText(request.getDescription()))
                .baseUrl(sanitizeOptionalText(request.getBaseUrl()))
                .modelName(sanitizeText(request.getModelName()))
                .modelNameSmall(sanitizeOptionalText(request.getModelNameSmall()))
                .iconUrl(sanitizeOptionalText(request.getIconUrl()))
                .isBuiltin(false)
                .isActive(true)
                .sortOrder(100)
                .build();

        provider = providerRepository.save(provider);
        log.info("Created custom provider: {} for user: {}", provider.getCode(), userId);
        return mapToProviderInfo(provider);
    }

    @Transactional
    public ProviderDto.ProviderInfo updateProvider(Long userId, String code, ProviderDto.UpdateRequest request) {
        ModelProvider provider = providerRepository.findByCode(code)
                .orElseThrow(() -> new BusinessException(ResponseCode.PROVIDER_NOT_FOUND));

        if (provider.getIsBuiltin()) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "Cannot modify built-in providers");
        }

        if (request.getName() != null) provider.setName(sanitizeOptionalText(request.getName()));
        if (request.getDescription() != null) provider.setDescription(sanitizeOptionalText(request.getDescription()));
        if (request.getBaseUrl() != null) provider.setBaseUrl(sanitizeOptionalText(request.getBaseUrl()));
        if (request.getModelName() != null) provider.setModelName(sanitizeOptionalText(request.getModelName()));
        if (request.getModelNameSmall() != null) provider.setModelNameSmall(sanitizeOptionalText(request.getModelNameSmall()));
        if (request.getIsActive() != null) provider.setIsActive(request.getIsActive());
        if (request.getIconUrl() != null) provider.setIconUrl(sanitizeOptionalText(request.getIconUrl()));

        provider = providerRepository.save(provider);
        log.info("Updated provider: {} by user: {}", provider.getCode(), userId);
        return mapToProviderInfo(provider);
    }

    /**
     * Update provider configuration (baseUrl, modelName, modelNameSmall).
     * Syncs settings.json immediately when the updated provider is currently active.
     */
    @Transactional
    public ProviderDto.ProviderInfo updateProviderConfig(Long userId, String code, ProviderDto.ConfigUpdateRequest request) {
        ModelProvider provider = providerRepository.findByCode(code)
                .orElseThrow(() -> new BusinessException(ResponseCode.PROVIDER_NOT_FOUND));

        String sanitizedBaseUrl = sanitizeOptionalText(request.getBaseUrl());
        String sanitizedModelName = sanitizeOptionalText(request.getModelName());
        String sanitizedModelNameSmall = sanitizeOptionalText(request.getModelNameSmall());

        if (CLAUDE_PROVIDER_CODE.equals(code)) {
            if (!CLAUDE_OFFICIAL_BASE_URL.equals(provider.getBaseUrl())) {
                provider.setBaseUrl(CLAUDE_OFFICIAL_BASE_URL);
            }
            if (!CLAUDE_DEFAULT_MODEL.equals(provider.getModelName())) {
                provider.setModelName(CLAUDE_DEFAULT_MODEL);
            }
            if (!CLAUDE_DEFAULT_SMALL_MODEL.equals(provider.getModelNameSmall())) {
                provider.setModelNameSmall(CLAUDE_DEFAULT_SMALL_MODEL);
            }
        } else if (sanitizedBaseUrl != null && !sanitizedBaseUrl.isEmpty()) {
            provider.setBaseUrl(sanitizedBaseUrl);
        }

        if (!CLAUDE_PROVIDER_CODE.equals(code) && request.getModelName() != null) {
            provider.setModelName(sanitizedModelName == null ? "" : sanitizedModelName);
        }
        if (!CLAUDE_PROVIDER_CODE.equals(code) && request.getModelNameSmall() != null) {
            provider.setModelNameSmall(sanitizedModelNameSmall == null || sanitizedModelNameSmall.isEmpty() ? null : sanitizedModelNameSmall);
        }

        provider = providerRepository.save(provider);
        syncSettingsIfCurrentProvider(userId, provider);
        log.info("Updated provider config: {} by user: {}, model: {}", provider.getCode(), userId, provider.getModelName());
        return mapToProviderInfo(provider);
    }

    private void syncSettingsIfCurrentProvider(Long userId, ModelProvider provider) {
        configRepository.findByUserId(userId)
                .filter(config -> provider.getCode().equals(config.getCurrentProvider().getCode()))
                .ifPresent(config -> settingsWriterService.writeToSettings(userId, provider));
    }

    /**
     * Test API connection for a provider.
     * Uses provided config or falls back to stored config.
     */
    public ProviderDto.TestResult testProviderConnection(Long userId, String code, ProviderDto.TestRequest request) {
        ModelProvider provider = providerRepository.findByCode(code)
                .orElseThrow(() -> new BusinessException(ResponseCode.PROVIDER_NOT_FOUND));

        // Use request values or fall back to stored values
        String baseUrl = request.getBaseUrl() != null && !request.getBaseUrl().isEmpty()
                ? request.getBaseUrl()
                : provider.getBaseUrl();
        String modelName = request.getModelName() != null && !request.getModelName().isEmpty()
                ? request.getModelName()
                : provider.getModelName();

        if (baseUrl == null || baseUrl.isBlank()) {
            return ProviderDto.TestResult.builder()
                    .success(false)
                    .message("Base URL 未配置")
                    .build();
        }
        String normalizedUrl = normalizeBaseUrl(baseUrl);
        ProviderApiStyle apiStyle = resolveApiStyle(provider.getCode(), normalizedUrl);
        if (requiresModelNameForTest(apiStyle, normalizedUrl) && (modelName == null || modelName.isBlank())) {
            return ProviderDto.TestResult.builder()
                    .success(false)
                    .message("模型名称未配置")
                    .build();
        }

        // Get API key from request or from stored keys
        String apiKey = request.getApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            Optional<ApiKey> storedKey = apiKeyRepository.findByUserIdAndProviderId(userId, provider.getId());
            if (storedKey.isPresent()) {
                apiKey = encryptionService.decrypt(storedKey.get().getEncryptedKey());
            }
        }

        if (apiKey == null || apiKey.isEmpty()) {
            return ProviderDto.TestResult.builder()
                    .success(false)
                    .message("API Key 未配置")
                    .build();
        }

        return performTestRequest(provider.getCode(), normalizedUrl, modelName, apiKey);
    }

    private ProviderDto.TestResult performTestRequest(String providerCode, String normalizedUrl, String modelName, String apiKey) {
        long startTime = System.currentTimeMillis();
        String testUrl = "";

        try {
            String normalizedApiKey = normalizeApiKey(apiKey);
            ProviderApiStyle apiStyle = resolveApiStyle(providerCode, normalizedUrl);

            testUrl = switch (apiStyle) {
                case OPENROUTER, OPENAI -> buildChatCompletionsUrl(normalizedUrl);
                case ANTHROPIC -> buildMessagesTestUrl(normalizedUrl);
            };
            String requestBody = switch (apiStyle) {
                case OPENROUTER -> buildOpenRouterTestRequestBody(modelName);
                case OPENAI -> buildOpenAiCompatibleTestRequestBody(modelName);
                case ANTHROPIC -> buildAnthropicTestRequestBody(modelName);
            };

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(testUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(30));

            switch (apiStyle) {
                case OPENROUTER, OPENAI ->
                        requestBuilder.header("Authorization", "Bearer " + normalizedApiKey);
                case ANTHROPIC ->
                        requestBuilder
                                .header("x-api-key", normalizedApiKey)
                                .header("anthropic-version", "2023-06-01");
            }

            log.info("Test request -> provider={}, url={}, auth={}, apiKey={}, body={}",
                    providerCode,
                    testUrl,
                    authHeaderName(apiStyle),
                    maskApiKey(normalizedApiKey),
                    singleLine(requestBody));

            HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            log.info("Test response <- provider={}, url={}, status={}, body={}",
                    providerCode,
                    testUrl,
                    response.statusCode(),
                    truncate(response.body(), 4000));

            if (apiStyle == ProviderApiStyle.OPENROUTER && shouldRetryOpenRouterWithAvailableProviders(response)) {
                List<String> availableProviders = extractOpenRouterAvailableProviders(response.body());
                if (!availableProviders.isEmpty()) {
                    String retryRequestBody = buildOpenRouterTestRequestBody(modelName, availableProviders);
                    HttpRequest retryRequest = buildHttpRequest(testUrl, retryRequestBody, apiStyle, normalizedApiKey);
                    log.info("Test retry request -> provider={}, url={}, order={}, body={}",
                            providerCode,
                            testUrl,
                            availableProviders,
                            singleLine(retryRequestBody));
                    HttpResponse<String> retryResponse = httpClient.send(retryRequest, HttpResponse.BodyHandlers.ofString());
                    log.info("Test retry response <- provider={}, url={}, status={}, body={}",
                            providerCode,
                            testUrl,
                            retryResponse.statusCode(),
                            truncate(retryResponse.body(), 4000));
                    return buildTestResult(retryResponse, modelName, startTime);
                }
            }

            return buildTestResult(response, modelName, startTime);

        } catch (java.net.ConnectException e) {
            log.warn("Test connection connect exception: provider={}, url={}, message={}", providerCode, testUrl, e.getMessage());
            return ProviderDto.TestResult.builder()
                    .success(false)
                    .message("无法连接到服务器，请检查 Base URL")
                    .responseTimeMs(System.currentTimeMillis() - startTime)
                    .build();
        } catch (java.net.SocketTimeoutException e) {
            log.warn("Test connection timeout: provider={}, url={}, message={}", providerCode, testUrl, e.getMessage());
            return ProviderDto.TestResult.builder()
                    .success(false)
                    .message("连接超时，请检查网络或 Base URL")
                    .responseTimeMs(System.currentTimeMillis() - startTime)
                    .build();
        } catch (Exception e) {
            log.error("Test connection failed: provider={}, url={}, message={}", providerCode, testUrl, e.getMessage(), e);
            return ProviderDto.TestResult.builder()
                    .success(false)
                    .message("测试失败: " + e.getMessage())
                    .responseTimeMs(System.currentTimeMillis() - startTime)
                    .build();
        }
    }

    private String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return "***";
        }
        int length = apiKey.length();
        if (length <= 8) {
            return "***";
        }
        return apiKey.substring(0, 4) + "..." + apiKey.substring(length - 4);
    }

    private String singleLine(String value) {
        if (value == null) {
            return "";
        }
        return sanitizeText(value)
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...(truncated)";
    }

    private String normalizeApiKey(String apiKey) {
        if (apiKey == null) {
            return "";
        }
        String normalized = sanitizeText(apiKey);
        if (normalized.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return normalized.substring(7).trim();
        }
        return normalized;
    }

    private boolean isOpenRouterProvider(String providerCode, String normalizedUrl) {
        return "openrouter".equalsIgnoreCase(providerCode) || normalizedUrl.toLowerCase().contains("openrouter.ai");
    }

    private ProviderApiStyle resolveApiStyle(String providerCode, String normalizedUrl) {
        String lowerUrl = normalizedUrl.toLowerCase();
        if (isOpenRouterProvider(providerCode, normalizedUrl)) {
            return ProviderApiStyle.OPENROUTER;
        }
        if (isKimiCodingProvider(normalizedUrl)) {
            return ProviderApiStyle.ANTHROPIC;
        }
        if (isAnthropicCompatibleProvider(lowerUrl)) {
            return ProviderApiStyle.ANTHROPIC;
        }
        return ProviderApiStyle.OPENAI;
    }

    private boolean isAnthropicCompatibleProvider(String lowerUrl) {
        return lowerUrl.contains("anthropic.com")
                || lowerUrl.contains("/anthropic")
                || lowerUrl.endsWith("/messages")
                || lowerUrl.endsWith("/v1/messages");
    }

    private boolean isKimiCodingProvider(String normalizedUrl) {
        return normalizedUrl.toLowerCase().contains("api.kimi.com/coding");
    }

    private boolean requiresModelNameForTest(ProviderApiStyle apiStyle, String normalizedUrl) {
        if (apiStyle == ProviderApiStyle.ANTHROPIC && isKimiCodingProvider(normalizedUrl)) {
            return false;
        }
        return true;
    }

    private String authHeaderName(ProviderApiStyle apiStyle) {
        return apiStyle == ProviderApiStyle.ANTHROPIC ? "x-api-key" : "Authorization: Bearer";
    }

    private String buildChatCompletionsUrl(String normalizedUrl) {
        if (normalizedUrl.endsWith("/v1/chat/completions") || normalizedUrl.endsWith("/chat/completions")) {
            return normalizedUrl;
        }
        if (normalizedUrl.endsWith("/v1") || normalizedUrl.endsWith("/api/v3")) {
            return normalizedUrl + "/chat/completions";
        }
        return normalizedUrl + "/v1/chat/completions";
    }

    private String buildOpenAiCompatibleTestRequestBody(String modelName) {
        return """
            {
                "model": "%s",
                "max_tokens": 10,
                "messages": [{"role": "user", "content": "Hi"}]
            }
            """.formatted(modelName);
    }

    private String buildAnthropicTestRequestBody(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return """
                {
                    "max_tokens": 10,
                    "messages": [{"role": "user", "content": "Hi"}]
                }
                """;
        }
        return """
            {
                "model": "%s",
                "max_tokens": 10,
                "messages": [{"role": "user", "content": "Hi"}]
            }
            """.formatted(modelName);
    }

    private String buildOpenRouterTestRequestBody(String modelName) {
        String providerHint = extractModelProviderHint(modelName);
        return buildOpenRouterTestRequestBody(modelName,
                providerHint == null ? List.of() : List.of(providerHint));
    }

    private String buildOpenRouterTestRequestBody(String modelName, List<String> providerOrder) {
        if (providerOrder == null || providerOrder.isEmpty()) {
            return """
                {
                    "model": "%s",
                    "max_tokens": 10,
                    "messages": [{"role": "user", "content": "Hi"}]
                }
                """.formatted(modelName);
        }

        String orderJson = providerOrder.stream()
                .map(provider -> "\"" + provider + "\"")
                .collect(Collectors.joining(", "));
        return """
            {
                "model": "%s",
                "max_tokens": 10,
                "messages": [{"role": "user", "content": "Hi"}],
                "provider": {
                    "order": [%s],
                    "allow_fallbacks": true
                }
            }
            """.formatted(modelName, orderJson);
    }

    private String extractModelProviderHint(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return null;
        }
        String normalized = modelName.trim();
        int slashIndex = normalized.indexOf('/');
        if (slashIndex <= 0) {
            return null;
        }
        String providerHint = normalized.substring(0, slashIndex).trim().toLowerCase();
        return providerHint.isEmpty() ? null : providerHint;
    }

    private ProviderDto.TestResult buildTestResult(HttpResponse<String> response, String modelName, long startTime) {
        long responseTime = System.currentTimeMillis() - startTime;

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return ProviderDto.TestResult.builder()
                    .success(true)
                    .message("连接成功")
                    .modelName(modelName)
                    .responseTimeMs(responseTime)
                    .build();
        } else if (response.statusCode() == 401) {
            return ProviderDto.TestResult.builder()
                    .success(false)
                    .message("API Key 无效")
                    .responseTimeMs(responseTime)
                    .build();
        } else if (response.statusCode() == 404) {
            String errorMsg = extractErrorMessage(response.body());
            return ProviderDto.TestResult.builder()
                    .success(false)
                    .message("请求失败: " + errorMsg)
                    .responseTimeMs(responseTime)
                    .build();
        } else {
            String errorMsg = extractErrorMessage(response.body());
            return ProviderDto.TestResult.builder()
                    .success(false)
                    .message("请求失败: " + errorMsg)
                    .responseTimeMs(responseTime)
                    .build();
        }
    }

    private HttpRequest buildHttpRequest(String testUrl, String requestBody, ProviderApiStyle apiStyle, String apiKey) {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(testUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofSeconds(30));

        if (apiStyle == ProviderApiStyle.ANTHROPIC) {
            requestBuilder
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01");
        } else {
            requestBuilder.header("Authorization", "Bearer " + apiKey);
        }
        return requestBuilder.build();
    }

    private boolean shouldRetryOpenRouterWithAvailableProviders(HttpResponse<String> response) {
        if (response.statusCode() != 404 || response.body() == null) {
            return false;
        }
        String body = response.body();
        return body.contains("No allowed providers are available")
                && body.contains("available_providers");
    }

    private List<String> extractOpenRouterAvailableProviders(String responseBody) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(responseBody);
            com.fasterxml.jackson.databind.JsonNode providersNode = root.path("error")
                    .path("metadata")
                    .path("available_providers");
            if (!providersNode.isArray()) {
                return List.of();
            }
            List<String> providers = new java.util.ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode node : providersNode) {
                String provider = node.asText();
                if (provider != null && !provider.isBlank()) {
                    providers.add(provider.trim().toLowerCase());
                }
            }
            return providers;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private String normalizeBaseUrl(String baseUrl) {
        String normalizedUrl = sanitizeText(baseUrl);
        if (!normalizedUrl.startsWith("http://") && !normalizedUrl.startsWith("https://")) {
            normalizedUrl = "https://" + normalizedUrl;
        }
        while (normalizedUrl.endsWith("/")) {
            normalizedUrl = normalizedUrl.substring(0, normalizedUrl.length() - 1);
        }
        return normalizedUrl;
    }

    private String sanitizeText(String value) {
        if (value == null) {
            return "";
        }
        return value
                .trim()
                // 移除所有不可见的特殊字符（零宽度空格等控制字符）
                .replaceAll("[\\x00-\\x1F\\x7F-\\x9F\\u200b-\\u200d\\ufeff]", "");
    }

    private String sanitizeOptionalText(String value) {
        if (value == null) {
            return null;
        }
        return sanitizeText(value);
    }

    private String sanitizeCode(String value) {
        return sanitizeText(value).toLowerCase();
    }

    private String buildMessagesTestUrl(String normalizedUrl) {
        if (normalizedUrl.endsWith("/v1/messages") || normalizedUrl.endsWith("/messages")) {
            return normalizedUrl;
        }
        if (normalizedUrl.endsWith("/v1")) {
            return normalizedUrl + "/messages";
        }
        return normalizedUrl + "/v1/messages";
    }

    private String extractErrorMessage(String responseBody) {
        if (responseBody == null || responseBody.isEmpty()) {
            return "未知错误";
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(responseBody);
            if (root.has("error")) {
                com.fasterxml.jackson.databind.JsonNode error = root.get("error");
                if (error.has("message")) {
                    return error.get("message").asText();
                }
                return error.asText();
            }
        } catch (Exception ignored) {
        }
        if (responseBody.length() > 100) {
            return responseBody.substring(0, 100) + "...";
        }
        return responseBody;
    }

    private ProviderDto.ProviderInfo mapToProviderInfo(ModelProvider provider) {
        return ProviderDto.ProviderInfo.builder()
                .id(provider.getId())
                .code(provider.getCode())
                .name(provider.getName())
                .description(provider.getDescription())
                .baseUrl(provider.getBaseUrl())
                .modelName(provider.getModelName())
                .modelNameSmall(provider.getModelNameSmall())
                .isBuiltin(provider.getIsBuiltin())
                .isActive(provider.getIsActive())
                .sortOrder(provider.getSortOrder())
                .iconUrl(provider.getIconUrl())
                .createdAt(provider.getCreatedAt())
                .build();
    }
}
